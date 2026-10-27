package org.hostapptv.player.playback

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.hostapptv.player.data.xtream.XEpgEntry
import org.hostapptv.player.data.xtream.XEpisode
import org.hostapptv.player.ui.components.ButtonTone
import org.hostapptv.player.ui.components.Emblem
import org.hostapptv.player.ui.components.LiveBadge
import org.hostapptv.player.ui.components.ProgressLine
import org.hostapptv.player.ui.components.TvButton
import org.hostapptv.player.ui.theme.AppIcons
import org.hostapptv.player.ui.theme.Dimens
import org.hostapptv.player.ui.theme.Ink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Panel { NONE, AUDIO, SUBTITLES }

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    requestFlow: StateFlow<PlaybackRequest?>,
    controllerFlow: StateFlow<PlaybackController?>,
    epgFlow: StateFlow<List<XEpgEntry>>,
    nextEpisodeFlow: StateFlow<XEpisode?>,
    onExit: () -> Unit,
    onResumeAfterBackground: () -> Unit,
    onChannelStep: (Int) -> Unit,
    onPlayNext: () -> Unit,
    onRequestNextEpisode: () -> Unit,
) {
    val request by requestFlow.collectAsState()
    val controller by controllerFlow.collectAsState()
    val state by (controller?.state ?: remember { flowOf(PlayerUiState(phase = Phase.SUSPENDED)) }).collectAsState(PlayerUiState(phase = Phase.SUSPENDED))
    val epg by epgFlow.collectAsState()
    val nextEpisode by nextEpisodeFlow.collectAsState()
    val req = request ?: return
    val isLive = req.kind == ContentKind.LIVE

    var controlsVisible by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var seekTarget by remember { mutableStateOf<Long?>(null) }
    var interaction by remember { mutableIntStateOf(0) }
    var infoFlash by remember { mutableIntStateOf(1) }
    var collapsingControls by remember { mutableStateOf(false) }

    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val audioFocus = remember { FocusRequester() }
    val subsFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    val errorFocus = remember { FocusRequester() }

    val blocking = state.phase == Phase.ERROR || state.phase == Phase.ENDED || (state.phase == Phase.IDLE && isLive)
    val recovering = state.phase in listOf(Phase.LOADING, Phase.RETRYING, Phase.BUFFERING)

    // Auto-hide only while playing and idle; paused state keeps controls.
    LaunchedEffect(controlsVisible, interaction, state.phase, panel) {
        if (controlsVisible && panel == Panel.NONE && state.phase == Phase.PLAYING) {
            delay(5_000); controlsVisible = false
        }
    }
    LaunchedEffect(infoFlash) { if (infoFlash > 0) { delay(4_000); infoFlash = 0 } }
    LaunchedEffect(state.phase) {
        if (state.phase == Phase.PAUSED) controlsVisible = true
        if (state.phase == Phase.ENDED && req.kind == ContentKind.EPISODE) onRequestNextEpisode()
    }
    LaunchedEffect(controlsVisible, panel, blocking) {
        runCatching {
            when {
                blocking || panel != Panel.NONE -> Unit // the panel focuses its own first action
                controlsVisible -> { delay(16); playFocus.requestFocus() }
                else -> rootFocus.requestFocus()
            }
        }
        if (controlsVisible && req.kind == ContentKind.EPISODE) onRequestNextEpisode()
    }

    BackHandler {
        when {
            recovering -> onExit()
            panel != Panel.NONE -> { val from = panel; panel = Panel.NONE; runCatching { (if (from == Panel.AUDIO) audioFocus else subsFocus).requestFocus() } }
            seekTarget != null -> seekTarget = null
            controlsVisible && !blocking -> controlsVisible = false
            else -> onExit()
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown) interaction++
                if (ev.key == Key.DirectionUp && (collapsingControls || (controlsVisible && panel == Panel.NONE && !blocking))) {
                    collapsingControls = ev.type != KeyEventType.KeyUp
                    controlsVisible = false
                    true
                } else false
            }
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                val c = controller
                when (ev.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { c?.togglePlayPause(); controlsVisible = true; true }
                    Key.MediaFastForward -> { if (state.seekable) c?.seekTo(state.positionMs + 30_000); true }
                    Key.MediaRewind -> { if (state.seekable) c?.seekTo(state.positionMs - 30_000); true }
                    Key.ChannelUp -> { if (isLive) { onChannelStep(-1); infoFlash++ }; true }
                    Key.ChannelDown -> { if (isLive) { onChannelStep(1); infoFlash++ }; true }
                    else -> false
                }
            }
            .semantics { contentDescription = "Video player, ${req.title}" },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    isFocusable = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(false)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            },
            update = { view -> if (view.player !== controller?.player) view.player = controller?.player },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        // Hidden-overlay key target.
        Box(
            Modifier.fillMaxSize().focusRequester(rootFocus).focusable()
                .onKeyEvent { ev ->
                    if (controlsVisible || blocking) return@onKeyEvent false
                    val c = controller
                    // Commit on key-up so revealing controls or buffering cannot pass the
                    // same OK release into a newly focused button.
                    if (ev.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
                        if (ev.type == KeyEventType.KeyUp) {
                            val target = seekTarget
                            if (target != null) { c?.seekTo(target); seekTarget = null } else controlsVisible = true
                        }
                        return@onKeyEvent true
                    }
                    if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (ev.key) {
                        Key.DirectionLeft, Key.DirectionRight -> {
                            if (state.seekable) {
                                val step = if (ev.key == Key.DirectionLeft) -10_000L else 10_000L
                                val dur = state.durationMs.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
                                seekTarget = ((seekTarget ?: state.positionMs) + step).coerceIn(0, dur)
                            } else controlsVisible = true
                            true
                        }
                        Key.DirectionUp, Key.DirectionDown -> {
                            if (isLive) { onChannelStep(if (ev.key == Key.DirectionUp) -1 else 1); infoFlash++ } else controlsVisible = true
                            true
                        }
                        Key.Menu -> { controlsVisible = true; true }
                        else -> false
                    }
                },
        )

        val showInfo = controlsVisible || infoFlash > 0 || state.phase == Phase.LOADING || state.phase == Phase.RETRYING
        AnimatedVisibility(showInfo && !blocking, enter = fadeIn(tween(140)), exit = fadeOut(tween(160)), modifier = Modifier.align(Alignment.TopStart)) {
            TopInfo(req, epg, state)
        }

        if ((state.phase == Phase.LOADING || state.phase == Phase.RETRYING || state.phase == Phase.BUFFERING) && !blocking) {
            LoadingBadge(state, isLive, Modifier.align(Alignment.Center))
        }

        seekTarget?.let { target ->
            if (!controlsVisible) SeekPreview(target, state, Modifier.align(Alignment.BottomCenter))
        }

        AnimatedVisibility(controlsVisible && !blocking, enter = fadeIn(tween(140)), exit = fadeOut(tween(160)), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(
                Modifier.fillMaxWidth()
                    .testTag("player-controls")
                    .background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Ink.canvas.copy(alpha = 0.82f), 1f to Ink.canvas.copy(alpha = 0.96f)))
                    .padding(start = Dimens.safeH, end = Dimens.safeH, bottom = Dimens.safeV, top = 48.dp),
            ) {
                if (state.seekable) {
                    val dur = state.durationMs.coerceAtLeast(1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatTime(state.positionMs), style = MaterialTheme.typography.labelMedium, color = Ink.text)
                        Spacer(Modifier.width(14.dp))
                        ProgressLine(state.positionMs.toFloat() / dur, Modifier.weight(1f).semantics { stateDescription = "${formatTime(state.positionMs)} of ${formatTime(state.durationMs)}" })
                        Spacer(Modifier.width(14.dp))
                        Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelMedium, color = Ink.text)
                    }
                    Spacer(Modifier.height(16.dp))
                } else if (isLive) {
                    LiveBadge(); Spacer(Modifier.height(14.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val playing = state.phase == Phase.PLAYING || state.phase == Phase.BUFFERING
                    TvButton(if (playing) "Pause" else "Play", { controller?.togglePlayPause() }, Modifier.focusRequester(playFocus),
                        icon = if (playing) AppIcons.Pause else AppIcons.Play, tone = ButtonTone.Primary)
                    if (state.seekable) {
                        TvButton("10s", { controller?.seekTo((controller?.player?.currentPosition ?: state.positionMs) - 10_000) },
                            Modifier.testTag("player-seek-back"), icon = AppIcons.Rewind)
                        TvButton("10s", { controller?.seekTo((controller?.player?.currentPosition ?: state.positionMs) + 10_000) },
                            Modifier.testTag("player-seek-forward"), icon = AppIcons.Forward)
                    }
                    if (isLive) {
                        TvButton("Previous", { onChannelStep(-1) })
                        TvButton("Next", { onChannelStep(1) })
                    }
                    TvButton("Audio", { panel = Panel.AUDIO }, Modifier.focusRequester(audioFocus), icon = AppIcons.Audio, enabled = state.audioTracks.size > 1)
                    TvButton(if (state.subtitlesOff) "Subtitles off" else "Subtitles", { panel = Panel.SUBTITLES }, Modifier.focusRequester(subsFocus),
                        icon = AppIcons.Subtitles, enabled = state.textTracks.isNotEmpty())
                    if (req.kind == ContentKind.EPISODE && nextEpisode != null) TvButton("Next episode", onPlayNext, icon = AppIcons.Next)
                    Spacer(Modifier.weight(1f))
                    state.videoFormat?.let { Text(it, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = MaterialTheme.typography.bodySmall.letterSpacing), color = Ink.muted, maxLines = 1) }
                }
                state.subtitleNotice?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Ink.muted, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }

        if (panel != Panel.NONE) {
            TrackPanel(
                title = if (panel == Panel.AUDIO) "Audio" else "Subtitles",
                options = if (panel == Panel.AUDIO) state.audioTracks else state.textTracks,
                includeOff = panel == Panel.SUBTITLES, offSelected = state.subtitlesOff,
                firstFocus = panelFocus,
                onSelect = { opt ->
                    if (panel == Panel.AUDIO) opt?.let { controller?.selectAudio(it) } else controller?.selectText(opt)
                    val from = panel; panel = Panel.NONE
                    runCatching { (if (from == Panel.AUDIO) audioFocus else subsFocus).requestFocus() }
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        if (blocking) {
            BlockingPanel(req, state, nextEpisode, errorFocus, controller, onExit, onResumeAfterBackground, onPlayNext, Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun TopInfo(req: PlaybackRequest, epg: List<XEpgEntry>, state: PlayerUiState) {
    Column(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(0f to Ink.canvas.copy(alpha = 0.94f), 0.65f to Ink.canvas.copy(alpha = 0.8f), 1f to Color.Transparent))
            .padding(start = Dimens.safeH, end = Dimens.safeH, top = Dimens.safeV, bottom = 36.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (req.kind == ContentKind.LIVE) { LiveBadge(); Spacer(Modifier.width(14.dp)) }
            Text(listOfNotNull(req.subtitle?.takeIf { req.kind == ContentKind.LIVE }, req.title).joinToString("  "),
                style = MaterialTheme.typography.headlineSmall, color = Ink.heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (req.kind != ContentKind.LIVE && req.subtitle != null) {
            Text(req.subtitle, style = MaterialTheme.typography.bodyMedium, color = Ink.text, maxLines = 1)
        }
        if (req.kind == ContentKind.LIVE) {
            Spacer(Modifier.height(6.dp))
            if (epg.isEmpty()) Text("Programme information unavailable", style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
            epg.take(2).forEachIndexed { i, e ->
                Text(
                    "${if (i == 0) "Now" else "Next"}  ${e.startEpochSec?.let(::clock) ?: ""}  ${e.title}",
                    style = MaterialTheme.typography.bodyMedium, color = if (i == 0) Ink.text else Ink.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LoadingBadge(state: PlayerUiState, isLive: Boolean, modifier: Modifier) {
    Column(modifier.background(Ink.scrim, MaterialTheme.shapes.medium).padding(horizontal = 28.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Emblem(width = 30.dp)
        Spacer(Modifier.height(10.dp))
        Text(
            when (state.phase) {
                Phase.RETRYING -> if (isLive) "Reconnecting… ${state.recoverySecondsLeft}s left" else "Reconnecting… attempt ${state.retryAttempt}"
                Phase.BUFFERING -> if (isLive) "Buffering… ${state.recoverySecondsLeft}s left" else "Buffering…"
                else -> if (isLive) "Opening stream… ${state.recoverySecondsLeft}s left" else "Opening stream…"
            },
            style = MaterialTheme.typography.bodyMedium, color = Ink.text,
        )
        Spacer(Modifier.height(10.dp))
        Text("Back to leave", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
    }
}

@Composable
private fun SeekPreview(target: Long, state: PlayerUiState, modifier: Modifier) {
    Column(modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Ink.canvas.copy(alpha = 0.92f))))
        .padding(start = Dimens.safeH, end = Dimens.safeH, bottom = Dimens.safeV, top = 30.dp)) {
        Text("Seek to ${formatTime(target)}  ·  OK to jump, Back to cancel", style = MaterialTheme.typography.titleSmall, color = Ink.text)
        Spacer(Modifier.height(10.dp))
        ProgressLine(target.toFloat() / state.durationMs.coerceAtLeast(1), Modifier.fillMaxWidth())
    }
}

@Composable
private fun TrackPanel(
    title: String, options: List<TrackOption>, includeOff: Boolean, offSelected: Boolean, firstFocus: FocusRequester,
    onSelect: (TrackOption?) -> Unit, modifier: Modifier,
) {
    Column(modifier.fillMaxHeight().width(320.dp).background(Ink.surface).padding(horizontal = 24.dp, vertical = Dimens.safeV)) {
        LaunchedEffect(Unit) { delay(32); runCatching { firstFocus.requestFocus() } }
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Ink.heading)
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (includeOff) item {
                TvButton("Off", { onSelect(null) }, Modifier.fillMaxWidth().focusRequester(firstFocus), icon = if (offSelected) AppIcons.Check else null, selected = offSelected)
            }
            items(options.size) { i ->
                val o = options[i]
                val m = if (!includeOff && i == 0) Modifier.fillMaxWidth().focusRequester(firstFocus) else Modifier.fillMaxWidth()
                TvButton(o.label, { onSelect(o) }, m, icon = if (o.selected) AppIcons.Check else null, selected = o.selected)
            }
        }
    }
}

@Composable
private fun BlockingPanel(
    req: PlaybackRequest, state: PlayerUiState, nextEpisode: XEpisode?, focus: FocusRequester, controller: PlaybackController?,
    onExit: () -> Unit, onResume: () -> Unit, onPlayNext: () -> Unit, modifier: Modifier,
) {
    val (title, body) = when {
        state.phase == Phase.IDLE -> "Live TV paused" to "Playback stopped while you were away. Nothing is streaming."
        state.phase == Phase.ENDED -> (if (nextEpisode != null) "Up next" else "Finished") to
            (nextEpisode?.let { "Season ${it.season}, episode ${it.episodeNum}: ${it.title}" } ?: req.title)
        else -> when (state.failure) {
            FailureClass.AUTH -> "The provider refused this stream" to "Your account may have expired or reached its connection limit."
            FailureClass.UNAVAILABLE -> "Not available from the provider" to "This stream may have been removed. Your favourites and progress are unaffected."
            FailureClass.UNSUPPORTED -> "This device can't play this edition" to (state.errorDetail?.takeIf { it.contains("decoder") }
                ?: "The format isn't supported here. If another language or quality exists, choose it from the title page.")
            FailureClass.CLEARTEXT -> "Unencrypted connection blocked" to "Allow HTTP for this provider in Settings to play this stream."
            else -> "The stream isn't responding" to "Automatic reconnection stopped after ${state.retryAttempt.coerceAtLeast(1).let { if (it == 1) "1 attempt" else "$it attempts" }}. Check the connection, then try again."
        }
    }
    Column(
        modifier.width(560.dp).background(Ink.surface, MaterialTheme.shapes.large).padding(32.dp)
            .semantics(mergeDescendants = false) { contentDescription = "$title. $body" },
    ) {
        // Focus is requested here, once the panel exists; requesting it from the parent races composition.
        LaunchedEffect(state.phase, state.failure, nextEpisode != null) { delay(32); runCatching { focus.requestFocus() } }
        Text(title, style = MaterialTheme.typography.headlineSmall, color = if (state.phase == Phase.ERROR) Ink.danger else Ink.heading)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
        if (state.phase == Phase.ERROR && state.errorDetail != null && state.failure != FailureClass.UNSUPPORTED) {
            Spacer(Modifier.height(6.dp))
            Text(state.errorDetail, style = MaterialTheme.typography.bodySmall, color = Ink.muted.copy(alpha = 0.8f))
        }
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                state.phase == Phase.IDLE -> TvButton("Watch live", onResume, Modifier.focusRequester(focus), icon = AppIcons.Play, tone = ButtonTone.Primary)
                state.phase == Phase.ENDED && nextEpisode != null -> TvButton("Play next episode", onPlayNext, Modifier.focusRequester(focus), icon = AppIcons.Next, tone = ButtonTone.Primary)
                state.phase == Phase.ENDED -> TvButton("Play again", { controller?.togglePlayPause() }, Modifier.focusRequester(focus), icon = AppIcons.Refresh, tone = ButtonTone.Primary)
                state.failure == FailureClass.TRANSIENT || state.failure == FailureClass.AUTH ->
                    TvButton("Try again", { controller?.retryNow() }, Modifier.focusRequester(focus), icon = AppIcons.Refresh, tone = ButtonTone.Primary)
                else -> TvButton(if (req.kind == ContentKind.MOVIE && req.groupKey != null) "Choose another edition" else "Back", onExit,
                    Modifier.focusRequester(focus), icon = AppIcons.Back, tone = ButtonTone.Primary)
            }
            if (!(state.phase == Phase.ERROR && state.failure != FailureClass.TRANSIENT && state.failure != FailureClass.AUTH)) TvButton("Back", onExit, icon = AppIcons.Back)
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms == C.TIME_UNSET || ms < 0) return "--:--"
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun clock(epochSec: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochSec * 1000))
