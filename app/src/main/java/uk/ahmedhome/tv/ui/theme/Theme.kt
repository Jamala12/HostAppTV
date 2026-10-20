package uk.ahmedhome.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import uk.ahmedhome.tv.R

/** Semantic roles mapped from the Ahmed Home web palette (docs/UX-BRIEF.md). Components use roles, never raw hex. */
@Immutable
object Ink {
    val canvas = Color(0xFF0B1013)
    val surface = Color(0xFF0E1518)
    val raised = Color(0xFF17221E)
    val control = Color(0xFF131C1E)
    val selected = Color(0xFF24352D)
    val text = Color(0xFFEEF1ED)
    val heading = Color(0xFFF2F0E2)
    val muted = Color(0xFF9CACA1)
    val sage = Color(0xFFCBDCAC)
    val sageStrong = Color(0xFFE0EBCB)
    val onSage = Color(0xFF172217)
    val selectedText = Color(0xFFD0E4B4)
    val focus = Color(0xFFD3E5B1)
    val divider = Color(0xFF26302F)
    val controlLine = Color(0xFF40513F)
    val emblemLine = Color(0xFF79936D)
    val live = Color(0xFF8DE0C1)
    val danger = Color(0xFFFFB5BD)
    val dangerSurface = Color(0xFF29191D)
    val scrim = Color(0xCC0B1013)
}

/** Spacing and sizing on the 960×540 dp reference composition. */
object Dimens {
    val safeH = 48.dp
    val safeV = 27.dp
    val railWidth = 168.dp
    val gap = 24.dp
    val unit = 8.dp
    val controlHeight = 46.dp
    val focusBorder = 2.dp
    val radiusSmall = 7.dp
    val radiusMedium = 14.dp
    val posterWidth = 112.dp
    val posterHeight = 168.dp
}

val Serif = FontFamily(Font(R.font.gelasio, FontWeight.Normal))

private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = Serif, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.5).sp, color = Ink.heading),
    headlineSmall = TextStyle(fontFamily = Serif, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp),
)

@Composable
fun AhmedHomeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ink.sage, onPrimary = Ink.onSage, primaryContainer = Ink.selected, onPrimaryContainer = Ink.selectedText,
            secondary = Ink.sageStrong, onSecondary = Ink.onSage,
            background = Ink.canvas, onBackground = Ink.text,
            surface = Ink.surface, onSurface = Ink.text, surfaceVariant = Ink.raised, onSurfaceVariant = Ink.muted,
            border = Ink.focus, borderVariant = Ink.divider,
            error = Ink.danger, onError = Ink.canvas, errorContainer = Ink.dangerSurface, onErrorContainer = Ink.danger,
            inverseSurface = Ink.text, inverseOnSurface = Ink.canvas,
        ),
        typography = AppTypography,
        content = content,
    )
}
