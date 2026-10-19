package uk.ahmedhome.tv.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import uk.ahmedhome.tv.BuildConfig
import uk.ahmedhome.tv.core.AppLog

enum class Phase { IDLE, LOADING, PLAYING, PAUSED, BUFFERING, RETRYING, ENDED, ERROR, SUSPENDED }

data class TrackOption(val groupIndex: Int, val trackIndex: Int, val label: String, val selected: Boolean)

data class PlayerUiState(
    val phase: Phase = Phase.IDLE,
    val positionMs: Long = 0,
    val durationMs: Long = C.TIME_UNSET,
    val bufferedMs: Long = 0,
    val isLive: Boolean = false,
    val seekable: Boolean = false,
    val retryAttempt: Int = 0,
    val failure: FailureClass? = null,
    val errorDetail: String? = null,
    val audioTracks: List<TrackOption> = emptyList(),
    val textTracks: List<TrackOption> = emptyList(),
    val subtitlesOff: Boolean = true,
    val videoFormat: String? = null,
    val videoDecoder: String? = null,
    val audioFormat: String? = null,
)

/**
 * Owns the single ExoPlayer instance for the playback screen. Exactly one media item is open at a time:
 * every [open] stops and clears the previous source before preparing the next, and a generation counter
 * stops stale retry/watchdog work from touching the newest selection.
 */
@OptIn(UnstableApi::class)
class PlaybackController(
    private val context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val preferredAudioLanguage: String?,
    private val onProgress: (positionMs: Long, durationMs: Long) -> Unit,
) {
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    var player: ExoPlayer? = null
        private set

    private var generation = 0
    private var currentUrl: String? = null
    private var currentKind: ContentKind? = null
    private val retry = RetryPolicy()
    private var retryJob: Job? = null
    private var watchdogJob: Job? = null
    private var tickerJob: Job? = null
    private var reachedReady = false
    private val gate = StreamGate()
    private var openJob: Job? = null

    private fun ensurePlayer(): ExoPlayer = player ?: run {
        val dataSource = OkHttpDataSource.Factory(http).setUserAgent("AhmedHomeTV/${BuildConfig.VERSION_NAME} (Linux; Android)")
        val loadControl = DefaultLoadControl.Builder()
            .setTargetBufferBytes(32 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBufferDurationsMs(15_000, 50_000, 2_000, 4_000)
            .build()
        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setPreferredAudioLanguage(preferredAudioLanguage)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        }
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(gate.wrap(dataSource)).setLoadErrorHandlingPolicy(FailFastLoadErrorPolicy()))
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setHandleAudioBecomingNoisy(true)
            .build().also { p ->
                p.addListener(listener)
                p.addAnalyticsListener(analytics)
                player = p
            }
    }

    fun open(request: PlaybackRequest, url: String, startPositionMs: Long, playWhenReady: Boolean = true) {
        val gen = ++generation
        cancelJobs()
        openJob?.cancel()
        retry.reset()
        reachedReady = false
        currentUrl = url
        currentKind = request.kind
        val p = ensurePlayer()
        p.stop()
        p.clearMediaItems()
        _state.value = PlayerUiState(phase = Phase.LOADING, isLive = request.kind == ContentKind.LIVE)
        // One provider stream at a time: wait for the previous connection to close before opening the next.
        // Rapid channel changes supersede each other here, so only the latest selection ever connects.
        openJob = scope.launch {
            if (!gate.awaitClosed()) AppLog.w("previous stream still closing after 2s; opening anyway")
            if (gen != generation) return@launch
            val item = MediaItem.Builder().setUri(url).setMimeType(mimeFor(request.ext)).build()
            if (request.kind == ContentKind.LIVE || startPositionMs <= 0) p.setMediaItem(item) else p.setMediaItem(item, startPositionMs)
            p.playWhenReady = playWhenReady
            p.prepare()
            startWatchdog(gen, PREPARE_TIMEOUT_MS)
            startTicker()
            AppLog.i("open ${request.kind} ${request.contentId} gen=$gen")
        }
    }

    /** Number of provider media connections currently open (diagnostics and tests). */
    val openConnections: StateFlow<Int> get() = gate.openConnections

    fun togglePlayPause() {
        val p = player ?: return
        if (_state.value.phase == Phase.ERROR) return
        if (p.playbackState == Player.STATE_ENDED) { p.seekToDefaultPosition(); p.play(); return }
        if (p.isPlaying || p.playWhenReady) { p.pause(); saveProgress() } else p.play()
    }

    fun play() { player?.play() }
    fun pause() { player?.pause(); saveProgress() }

    fun seekTo(positionMs: Long) {
        val p = player ?: return
        if (!p.isCurrentMediaItemSeekable) return
        val dur = p.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        p.seekTo(positionMs.coerceIn(0, dur))
        publishPosition()
    }

    /** User-initiated retry after an error: a fresh preparation of the same selection with a new budget. */
    fun retryNow() {
        val p = player ?: return
        generation++
        cancelJobs()
        retry.reset()
        reachedReady = false
        _state.update { it.copy(phase = Phase.LOADING, failure = null, errorDetail = null, retryAttempt = 0) }
        if (currentKind == ContentKind.LIVE) p.seekToDefaultPosition()
        p.prepare(); p.play()
        startWatchdog(generation, PREPARE_TIMEOUT_MS)
        startTicker()
    }

    fun selectAudio(option: TrackOption) = selectTrack(C.TRACK_TYPE_AUDIO, option)

    fun selectText(option: TrackOption?) {
        val p = player ?: return
        val builder = p.trackSelectionParameters.buildUpon()
        if (option == null) builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).clearOverridesOfType(C.TRACK_TYPE_TEXT)
        else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            val group = p.currentTracks.groups.getOrNull(option.groupIndex) ?: return
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
        }
        p.trackSelectionParameters = builder.build()
    }

    private fun selectTrack(type: Int, option: TrackOption) {
        val p = player ?: return
        val group = p.currentTracks.groups.getOrNull(option.groupIndex) ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex)).build()
    }

    /** Stop the stream but keep the player (used when leaving to the background is not the trigger). */
    fun stopStream() {
        generation++
        cancelJobs()
        saveProgress()
        player?.let { it.stop(); it.clearMediaItems() }
        _state.update { it.copy(phase = Phase.LOADING, failure = null, errorDetail = null) }
    }

    /** Release decoder, surface, buffers and the network connection. Called from onStop. */
    fun release() {
        generation++
        saveProgress()
        cancelJobs()
        player?.let {
            it.removeListener(listener)
            it.removeAnalyticsListener(analytics)
            it.release()
        }
        player = null
        currentUrl = null
        _state.update { it.copy(phase = Phase.SUSPENDED) }
    }

    fun saveProgress() {
        val p = player ?: return
        if (currentKind == ContentKind.LIVE || !reachedReady) return
        val dur = p.duration
        if (dur == C.TIME_UNSET || dur <= 0) return
        onProgress(p.currentPosition, dur)
    }

    private fun cancelJobs() {
        openJob?.cancel(); openJob = null
        retryJob?.cancel(); retryJob = null
        watchdogJob?.cancel(); watchdogJob = null
        tickerJob?.cancel(); tickerJob = null
    }

    private fun startWatchdog(gen: Int, timeoutMs: Long) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(timeoutMs)
            if (gen != generation) return@launch
            val p = player ?: return@launch
            if (p.playbackState == Player.STATE_BUFFERING || (p.playbackState == Player.STATE_IDLE && !reachedReady)) {
                AppLog.w("watchdog: no progress after ${timeoutMs}ms")
                handleFailure(FailureClass.TRANSIENT, "The stream stopped sending video")
            }
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var sinceSave = 0L
            while (isActive) {
                delay(1_000)
                val p = player ?: break
                publishPosition()
                if (p.isPlaying) {
                    retry.onHealthy()
                    sinceSave += 1_000
                    if (sinceSave >= 15_000) { sinceSave = 0; saveProgress() }
                }
            }
        }
    }

    private fun publishPosition() {
        val p = player ?: return
        _state.update {
            it.copy(
                positionMs = p.currentPosition.coerceAtLeast(0), durationMs = p.duration, bufferedMs = p.bufferedPosition,
                isLive = currentKind == ContentKind.LIVE || p.isCurrentMediaItemLive, seekable = p.isCurrentMediaItemSeekable && currentKind != ContentKind.LIVE,
            )
        }
    }

    private fun handleFailure(failure: FailureClass, detail: String?) {
        val p = player ?: return
        when (val d = retry.onFailure(failure)) {
            is RetryDecision.Retry -> {
                val gen = generation
                _state.update { it.copy(phase = Phase.RETRYING, retryAttempt = d.attempt, errorDetail = detail) }
                AppLog.i("retry ${d.attempt} in ${d.delayMs}ms ($failure)")
                retryJob?.cancel()
                // Close the stalled connection now; prepare() is a no-op unless the player is idle.
                if (p.playbackState != Player.STATE_IDLE) p.stop()
                watchdogJob?.cancel()
                retryJob = scope.launch {
                    delay(d.delayMs)
                    if (gen != generation) return@launch
                    if (failure == FailureClass.BEHIND_LIVE_WINDOW || currentKind == ContentKind.LIVE) p.seekToDefaultPosition()
                    p.prepare(); p.play()
                    startWatchdog(gen, RETRY_PREPARE_TIMEOUT_MS)
                }
            }
            is RetryDecision.GiveUp -> {
                generation++
                watchdogJob?.cancel()
                saveProgress()
                p.stop()
                _state.update { it.copy(phase = Phase.ERROR, failure = d.failure, errorDetail = detail) }
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val p = player ?: return
            when (playbackState) {
                Player.STATE_READY -> {
                    reachedReady = true
                    watchdogJob?.cancel()
                    _state.update { it.copy(phase = if (p.playWhenReady) Phase.PLAYING else Phase.PAUSED, failure = null) }
                    publishPosition()
                }
                Player.STATE_BUFFERING -> {
                    if (_state.value.phase != Phase.RETRYING && _state.value.phase != Phase.LOADING) _state.update { it.copy(phase = Phase.BUFFERING) }
                    if (p.playWhenReady) startWatchdog(generation, if (reachedReady) STALL_TIMEOUT_MS else PREPARE_TIMEOUT_MS)
                }
                Player.STATE_ENDED -> {
                    watchdogJob?.cancel()
                    if (currentKind != ContentKind.LIVE) { saveProgress(); _state.update { it.copy(phase = Phase.ENDED) } }
                    else handleFailure(FailureClass.TRANSIENT, "The live stream ended unexpectedly")
                }
                Player.STATE_IDLE -> Unit
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val p = player ?: return
            if (p.playbackState == Player.STATE_READY) _state.update { it.copy(phase = if (playWhenReady) Phase.PLAYING else Phase.PAUSED) }
            if (!playWhenReady) watchdogJob?.cancel()
        }

        override fun onPlayerError(error: PlaybackException) {
            val status = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            val failure = FailureClassifier.classify(error.errorCode, status)
            AppLog.w("player error ${error.errorCodeName} http=$status -> $failure", error)
            handleFailure(failure, status?.let { "Provider response: HTTP $it" } ?: describeError(error.errorCode))
        }

        override fun onTracksChanged(tracks: Tracks) {
            val audio = mutableListOf<TrackOption>(); val text = mutableListOf<TrackOption>()
            var textSelected = false
            tracks.groups.forEachIndexed { gi, g ->
                for (ti in 0 until g.length) {
                    if (!g.isTrackSupported(ti)) continue
                    val f = g.getTrackFormat(ti)
                    val opt = TrackOption(gi, ti, trackLabel(f, ti), g.isTrackSelected(ti))
                    when (g.type) {
                        C.TRACK_TYPE_AUDIO -> audio += opt
                        C.TRACK_TYPE_TEXT -> { text += opt; if (opt.selected) textSelected = true }
                    }
                }
            }
            _state.update { it.copy(audioTracks = audio, textTracks = text, subtitlesOff = !textSelected) }
        }
    }

    private val analytics = object : AnalyticsListener {
        private var softwareVideo = false
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            softwareVideo = isSoftwareDecoder(decoderName)
            _state.update { it.copy(videoDecoder = decoderName) }
            AppLog.i("video decoder: $decoderName")
            checkSoftware4k(player?.videoFormat)
        }
        override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            _state.update { it.copy(videoFormat = describe(format)) }
            checkSoftware4k(format)
        }
        override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            _state.update { it.copy(audioFormat = listOfNotNull(format.sampleMimeType?.substringAfter('/'), format.channelCount.takeIf { c -> c > 0 }?.let { c -> "${c}ch" }).joinToString(" ")) }
        }
        private fun checkSoftware4k(format: Format?) {
            if (softwareVideo && format != null && format.height > 1088) {
                AppLog.w("refusing software decode of ${format.width}x${format.height}")
                handleFailure(FailureClass.UNSUPPORTED, "This edition needs a hardware decoder this device does not offer (${format.width}×${format.height} ${format.sampleMimeType})")
            }
        }
    }

    // Declared last: the listeners above must be initialised before the player is built.
    init { ensurePlayer() }

    companion object {
        const val PREPARE_TIMEOUT_MS = 15_000L
        const val RETRY_PREPARE_TIMEOUT_MS = 10_000L
        const val STALL_TIMEOUT_MS = 20_000L

        fun describeError(code: Int): String = when (code) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Network connection failed"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "The connection timed out"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "Unsupported stream format"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "The stream data was damaged"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "No decoder on this device supports this video"
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> "The audio output could not start"
            else -> "Playback error ${code}"
        }

        fun isSoftwareDecoder(name: String): Boolean {
            val n = name.lowercase()
            return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.") || n.startsWith("c2.google.")
        }

        fun mimeFor(ext: String?): String? = when (ext?.lowercase()?.trimStart('.')) {
            "m3u8" -> MimeTypes.APPLICATION_M3U8
            "ts" -> MimeTypes.VIDEO_MP2T
            "mp4", "m4v" -> MimeTypes.VIDEO_MP4
            "mkv" -> MimeTypes.VIDEO_MATROSKA
            "webm" -> MimeTypes.VIDEO_WEBM
            else -> null
        }

        private fun describe(f: Format): String = buildString {
            append(f.sampleMimeType?.substringAfter('/') ?: "video")
            if (f.width > 0) append(" ${f.width}×${f.height}")
            if (f.frameRate > 0) append(" ${"%.0f".format(f.frameRate)}fps")
            if (f.bitrate > 0) append(" ${f.bitrate / 1000}kbps")
        }

        private fun trackLabel(f: Format, index: Int): String {
            val lang = f.language?.takeIf { it != "und" }?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }
            return listOfNotNull(f.label, lang, f.channelCount.takeIf { it > 0 }?.let { c -> if (c >= 6) "5.1" else if (c == 2) "Stereo" else "${c}ch" })
                .distinct().joinToString(" · ").ifBlank { "Track ${index + 1}" }
        }
    }
}
