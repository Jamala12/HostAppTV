package org.hostapptv.player.playback

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hostapptv.player.Session
import org.hostapptv.player.container
import org.hostapptv.player.data.db.ProgressEntity
import org.hostapptv.player.data.xtream.XEpgEntry
import org.hostapptv.player.data.xtream.XEpisode
import org.hostapptv.player.ui.theme.HostAppTVTheme

/**
 * Full-screen playback. The player exists only between onStart and onStop: leaving (Back, Home, sleep)
 * saves position and releases decoder, surface and stream. Returning never silently restarts live TV,
 * and VOD returns paused at the saved position.
 */
class PlayerActivity : ComponentActivity() {
    private var controller: PlaybackController? = null
    private lateinit var session: Session
    private val request = MutableStateFlow<PlaybackRequest?>(null)
    private val epg = MutableStateFlow<List<XEpgEntry>>(emptyList())
    private val nextEpisode = MutableStateFlow<XEpisode?>(null)
    private val controllerFlow = MutableStateFlow<PlaybackController?>(null)
    /** True after the activity was stopped once; the next start must not auto-play. */
    private var resumedFromBackground = false
    private var channelIds: List<Long>? = null
    private var startJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = container.session.value ?: container.restoreSession()
        val req = PlaybackRequest.fromIntent(intent)
        if (s == null || req == null) { finish(); return }
        session = s
        request.value = req
        resumedFromBackground = savedInstanceState?.getBoolean("background") == true
        savedInstanceState?.getLong("position", -1L)?.takeIf { it > 0 }?.let { request.value = req.copy(startPositionMs = it) }

        setContent {
            HostAppTVTheme {
                PlayerScreen(
                    requestFlow = request,
                    controllerFlow = controllerFlow,
                    epgFlow = epg,
                    nextEpisodeFlow = nextEpisode,
                    onExit = { finish() },
                    onResumeAfterBackground = { startPlayback(autoPlay = true) },
                    onChannelStep = ::stepChannel,
                    onPlayNext = ::playNextEpisode,
                    onRequestNextEpisode = ::loadNextEpisode,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!::session.isInitialized) return
        container.sync.setForeground(false)
        container.discovery.cancel()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val c = PlaybackController(this, container.http, lifecycleScope, null, ::persistProgress)
        controller = c
        controllerFlow.value = c
        val req = request.value ?: return
        when {
            !resumedFromBackground -> startPlayback(autoPlay = true)
            req.kind != ContentKind.LIVE -> startPlayback(autoPlay = false) // paused at saved position, explicit resume
            else -> Unit // live: wait for OK, never reopen a stream silently
        }
        if (req.kind == ContentKind.LIVE) loadEpg(req.contentId)
    }

    override fun onStop() {
        startJob?.cancel(); stepJob?.cancel()
        controller?.release()
        controller = null
        controllerFlow.value = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        resumedFromBackground = true
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("background", true)
        controller?.player?.currentPosition?.let { outState.putLong("position", it) }
    }

    private fun startPlayback(autoPlay: Boolean) {
        val c = controller ?: return
        val req = request.value ?: return
        startJob?.cancel()
        startJob = lifecycleScope.launch {
            val start = if (req.kind == ContentKind.LIVE) 0L else resumePosition(req)
            val settings = container.settings.current()
            val format = settings.liveFormat
            val url = when (req.kind) {
                ContentKind.LIVE -> session.client.liveUrl(req.contentId, format)
                ContentKind.MOVIE -> session.client.movieUrl(req.contentId, req.ext)
                ContentKind.EPISODE -> session.client.episodeUrl(req.contentId, req.ext)
            }
            // A newer channel or a stopped activity must not resurrect an older preparation.
            if (controller !== c || request.value?.contentId != req.contentId) return@launch
            c.setSubtitleMode(settings.subtitleMode)
            c.open(req, url, start, autoPlay)
            resumedFromBackground = false
        }
    }

    private suspend fun resumePosition(req: PlaybackRequest): Long {
        if (req.startPositionMs > 0) return req.startPositionMs
        val kind = if (req.kind == ContentKind.MOVIE) "movie" else "episode"
        val saved = withContext(Dispatchers.IO) { container.library.user.progress(session.accountId, kind, req.contentId) }
        return if (saved != null && !saved.finished) saved.positionMs else 0L
    }

    private fun persistProgress(positionMs: Long, durationMs: Long) {
        val req = request.value ?: return
        if (req.kind == ContentKind.LIVE) return
        val finished = durationMs > 0 && (positionMs >= durationMs * 0.92 || durationMs - positionMs < 90_000)
        val entity = ProgressEntity(
            accountId = session.accountId, kind = if (req.kind == ContentKind.MOVIE) "movie" else "episode",
            contentId = req.contentId, groupKey = req.groupKey, seriesId = req.seriesId, season = req.season, episode = req.episode,
            title = req.title, subtitle = req.subtitle, image = req.image, ext = req.ext,
            positionMs = positionMs, durationMs = durationMs, updatedAt = System.currentTimeMillis(), finished = finished,
        )
        container.appScope.launch(Dispatchers.IO) { container.library.saveProgress(entity) }
        request.value = req.copy(startPositionMs = if (finished) 0 else positionMs)
    }

    private fun loadEpg(streamId: Long) {
        epg.value = emptyList()
        lifecycleScope.launch {
            epg.value = runCatching { withContext(Dispatchers.IO) { session.client.shortEpg(streamId, 2) } }.getOrDefault(emptyList())
        }
    }

    private var stepJob: kotlinx.coroutines.Job? = null

    /**
     * Channel up/down: the banner follows every press immediately, the current stream stops at once, and only
     * the channel the viewer settles on is opened (350 ms debounce), so bursts never open intermediate streams.
     */
    private fun stepChannel(delta: Int) {
        val req = request.value ?: return
        if (req.kind != ContentKind.LIVE) return
        stepJob?.cancel()
        stepJob = lifecycleScope.launch {
            val ids = channelIds ?: withContext(Dispatchers.IO) {
                container.library.catalog.channelIdsFor(session.accountId, req.categoryId, req.favouritesOnly)
            }.also { channelIds = it }
            if (ids.isEmpty()) return@launch
            val idx = ids.indexOf(req.contentId).let { if (it < 0) 0 else it }
            val nextId = ids[(idx + delta).mod(ids.size)]
            val ch = withContext(Dispatchers.IO) { container.library.catalog.channel(session.accountId, nextId) } ?: return@launch
            request.value = req.copy(contentId = ch.streamId, title = ch.name, image = ch.icon, subtitle = ch.num?.toString())
            controller?.stopStream()
            kotlinx.coroutines.delay(350)
            loadEpg(ch.streamId)
            startPlayback(autoPlay = true)
        }
    }

    private fun loadNextEpisode() {
        val req = request.value ?: return
        if (req.kind != ContentKind.EPISODE || req.seriesId == null || nextEpisode.value != null) return
        lifecycleScope.launch {
            val info = runCatching { withContext(Dispatchers.IO) { session.client.seriesInfo(req.seriesId) } }.getOrNull() ?: return@launch
            val idx = info.episodes.indexOfFirst { it.id == req.contentId }
            nextEpisode.value = info.episodes.getOrNull(idx + 1).takeIf { idx >= 0 }
        }
    }

    private fun playNextEpisode() {
        val req = request.value ?: return
        val ep = nextEpisode.value ?: return
        controller?.saveProgress()
        nextEpisode.value = null
        request.value = req.copy(
            contentId = ep.id, ext = ep.extension, season = ep.season, episode = ep.episodeNum, startPositionMs = 0,
            subtitle = "S${ep.season} · E${ep.episodeNum} · ${ep.title}",
        )
        startPlayback(autoPlay = true)
    }
}
