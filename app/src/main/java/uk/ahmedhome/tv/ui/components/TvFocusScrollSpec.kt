package uk.ahmedhome.tv.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec

/** Keep headings, captions and focus borders within the TV viewport without repivoting settled focus. */
@OptIn(ExperimentalFoundationApi::class)
internal object TvFocusScrollSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // Scroll only when needed. Unconditional pivots on both nested axes can keep
        // requesting movement after focus has already settled, and clip the welcome.
        val top = minOf(containerSize * 0.18f, (containerSize - size).coerceAtLeast(0f))
        // Include the title/caption below the focusable artwork and the TV safe inset.
        val bottom = containerSize * 0.16f
        return when {
            offset < top -> offset - top
            offset + size > containerSize - bottom -> offset + size - containerSize + bottom
            else -> 0f
        }
    }
}
