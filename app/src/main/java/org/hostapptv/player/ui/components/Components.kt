package org.hostapptv.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import org.hostapptv.player.ui.theme.AppIcons
import org.hostapptv.player.ui.theme.Dimens
import org.hostapptv.player.ui.theme.Ink

/** HostAppTV asymmetric outlined emblem with the sage play mark. */
@Composable
fun Emblem(modifier: Modifier = Modifier, width: Dp = 34.dp) {
    Canvas(modifier.size(width, width * 1.13f)) {
        val sw = 1.dp.toPx() * 1.2f
        val w = size.width; val h = size.height
        val big = 12.dp.toPx() * (w / 38.dp.toPx()); val small = 4.dp.toPx() * (w / 38.dp.toPx())
        val path = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(
                left = sw / 2, top = sw / 2, right = w - sw / 2, bottom = h - sw / 2,
                topLeftCornerRadius = CornerRadius(big), topRightCornerRadius = CornerRadius(big),
                bottomRightCornerRadius = CornerRadius(small), bottomLeftCornerRadius = CornerRadius(big),
            ))
        }
        drawPath(path, Ink.emblemLine, style = Stroke(sw))
        val cx = w / 2 + w * 0.04f; val cy = h / 2; val t = w * 0.29f
        drawPath(Path().apply {
            moveTo(cx - t * 0.55f, cy - t * 0.72f); lineTo(cx + t * 0.78f, cy); lineTo(cx - t * 0.55f, cy + t * 0.72f); close()
        }, Ink.sage)
    }
}

@Composable
fun BrandLockup(modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Emblem(width = if (compact) 28.dp else 34.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text("HostAppTV", style = MaterialTheme.typography.titleMedium, color = Ink.text, maxLines = 1)
            Text("TV & CINEMA", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing), color = Ink.muted, maxLines = 1)
        }
    }
}

enum class ButtonTone { Primary, Secondary, Quiet }

/** The one button vocabulary: sage primary, raised secondary, quiet text. 2dp focus frame, ≤1.03 scale. */
@Composable
fun TvButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: ButtonTone = ButtonTone.Secondary,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val container = when (tone) { ButtonTone.Primary -> Ink.sage; ButtonTone.Secondary -> if (selected) Ink.selected else Ink.raised; ButtonTone.Quiet -> Color.Transparent }
    val content = when (tone) { ButtonTone.Primary -> Ink.onSage; else -> if (selected) Ink.selectedText else Ink.text }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = Dimens.controlHeight).semantics(mergeDescendants = true) {
            contentDescription = label
            if (selected) stateDescription = "Selected"
        },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(Dimens.radiusSmall + 2.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container, contentColor = content,
            focusedContainerColor = if (tone == ButtonTone.Primary) Ink.sageStrong else Ink.selected,
            focusedContentColor = if (tone == ButtonTone.Primary) Ink.onSage else Ink.text,
            pressedContainerColor = if (tone == ButtonTone.Primary) Ink.sage else Ink.raised,
            disabledContainerColor = container.copy(alpha = 0.4f), disabledContentColor = content.copy(alpha = 0.5f),
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(Dimens.focusBorder, Ink.focus), inset = (-3).dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp).heightIn(min = 26.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** Provider artwork resized to its slot; a titled panel stands in when artwork is missing or fails. */
@Composable
fun Artwork(url: String?, title: String, width: Dp, height: Dp, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val px = with(density) { Pair(width.roundToPx(), height.roundToPx()) }
    val fallback: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(Ink.raised).padding(10.dp), contentAlignment = Alignment.BottomStart) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Ink.muted, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
    Box(modifier.size(width, height)) {
        if (url.isNullOrBlank()) fallback() else SubcomposeAsyncImage(
            model = remember(url, px) { ImageRequest.Builder(context).data(url).size(px.first, px.second).precision(Precision.INEXACT).build() },
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
            loading = { Box(Modifier.fillMaxSize().background(Ink.raised)) },
            error = { fallback() },
        )
    }
}

/** One focus target per poster. Title is shown beneath, two lines max. */
@Composable
fun PosterCard(
    title: String,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    progress: Float? = null,
    width: Dp = Dimens.posterWidth,
    height: Dp = Dimens.posterHeight,
    onFocused: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(width)) {
        Surface(
            onClick = onClick,
            modifier = Modifier.size(width, height).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused?.invoke() }
                .semantics { contentDescription = listOfNotNull(title, caption).joinToString(", ") },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(Dimens.radiusSmall + 1.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Ink.raised, focusedContainerColor = Ink.raised),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(Dimens.focusBorder, Ink.focus), inset = (-4).dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        ) {
            Box {
                Artwork(image, title, width, height)
                if (progress != null && progress > 0f) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Ink.scrim)) {
                        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(Ink.sage))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.bodySmall, color = if (focused) Ink.text else Ink.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (caption != null) Text(caption, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = MaterialTheme.typography.bodySmall.letterSpacing), color = Ink.muted, maxLines = 1)
    }
}

/** Wide landscape tile for channels on Home. */
@Composable
fun ChannelTile(name: String, icon: String?, number: Int?, onClick: () -> Unit, modifier: Modifier = Modifier, onFocused: (() -> Unit)? = null) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(176.dp, 99.dp).onFocusChanged { if (it.isFocused) onFocused?.invoke() }
            .semantics { contentDescription = "$name, live channel" },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(Dimens.radiusMedium - 4.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Ink.raised, focusedContainerColor = Ink.selected),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(Dimens.focusBorder, Ink.focus), inset = (-4).dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp, 33.dp).clip(RoundedCornerShape(5.dp)).background(Ink.control), contentAlignment = Alignment.Center) {
                    if (!icon.isNullOrBlank()) Artwork(icon, "", 44.dp, 33.dp, contentScale = ContentScale.Fit)
                    else Text(name.take(2).uppercase(), style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                }
                Spacer(Modifier.weight(1f))
                LiveBadge()
            }
            Text(listOfNotNull(number?.toString(), name).joinToString("  "), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(Ink.live))
        Spacer(Modifier.width(5.dp))
        Text("LIVE", style = MaterialTheme.typography.labelSmall, color = Ink.live)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = Ink.text)
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            Text(trailing, style = MaterialTheme.typography.bodySmall, color = Ink.muted)
        }
    }
}

@Composable
fun PageHeading(title: String, modifier: Modifier = Modifier, detail: String? = null) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.displaySmall, color = Ink.heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = Ink.muted, maxLines = 1)
    }
}

/** Empty / error / status message with an optional single action; never a dead end. */
@Composable
fun StatusMessage(title: String, body: String?, modifier: Modifier = Modifier, isError: Boolean = false, action: (@Composable () -> Unit)? = null) {
    Column(modifier.padding(vertical = 24.dp).width(520.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isError) { Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(Ink.danger)); Spacer(Modifier.width(10.dp)) }
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (isError) Ink.danger else Ink.text)
        }
        if (body != null) { Spacer(Modifier.height(6.dp)); Text(body, style = MaterialTheme.typography.bodyMedium, color = Ink.muted) }
        if (action != null) { Spacer(Modifier.height(16.dp)); action() }
    }
}

/** Non-focusable loading placeholder rows; never steals focus or swallows Back. */
@Composable
fun SkeletonRow(count: Int = 5, width: Dp = Dimens.posterWidth, height: Dp = Dimens.posterHeight) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(count) { Box(Modifier.size(width, height).clip(RoundedCornerShape(Dimens.radiusSmall)).background(Ink.raised)) }
    }
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, track: Color = Ink.divider, color: Color = Ink.sage) {
    Canvas(modifier.height(4.dp)) {
        drawRoundRect(track, size = size, cornerRadius = CornerRadius(size.height / 2))
        drawRoundRect(color, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height), cornerRadius = CornerRadius(size.height / 2), topLeft = Offset.Zero)
    }
}

@Composable
fun IconLabel(icon: ImageVector, text: String, tint: Color = Ink.muted) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint, textAlign = TextAlign.Start)
    }
}

