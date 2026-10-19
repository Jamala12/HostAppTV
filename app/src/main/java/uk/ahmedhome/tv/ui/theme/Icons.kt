package uk.ahmedhome.tv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** One authored icon family: 24-unit grid, 1.8 stroke, round joins. Filled glyphs only for transport. */
object AppIcons {
    private fun stroke(name: String, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach {
            addPath(PathParser().parsePathString(it).toNodes(), stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()

    private fun fill(name: String, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { addPath(PathParser().parsePathString(it).toNodes(), fill = SolidColor(Color.White)) }
    }.build()

    val Search = stroke("search", "M18 11a7 7 0 1 1-14 0a7 7 0 1 1 14 0z", "M20 20l-4-4")
    val Home = stroke("home", "M3.5 11 12 4.5l8.5 6.5", "M5.5 9.5V19.5h13V9.5", "M10 19.5v-5.5h4v5.5")
    val Live = stroke("live", "M3.5 7.5h17v11h-17z", "M8.5 3.5 12 7l3.5-3.5")
    val Movie = stroke("movie", "M4 5h16v14H4z", "M8 5v14", "M16 5v14", "M4 9.7h4", "M4 14.3h4", "M16 9.7h4", "M16 14.3h4")
    val Series = stroke("series", "M12 4 3.5 8.5 12 13l8.5-4.5z", "M3.5 13 12 17.5l8.5-4.5", "M3.5 17 12 21.2l8.5-4.2")
    val MyList = stroke("mylist", "M6.5 4h11v16l-5.5-3.8L6.5 20z")
    val Settings = stroke("settings", "M4 7h9", "M17 7h3", "M4 17h3", "M11 17h9", "M15 5v4", "M9 15v4")
    val Play = fill("play", "M8 5.2v13.6a.6.6 0 0 0 .9.5l10.6-6.8a.6.6 0 0 0 0-1L8.9 4.7a.6.6 0 0 0-.9.5z")
    val Pause = fill("pause", "M7 5h3.6v14H7z", "M13.4 5H17v14h-3.6z")
    val Rewind = stroke("rewind", "M11 7 6 12l5 5", "M18 7l-5 5 5 5")
    val Forward = stroke("forward", "M13 7l5 5-5 5", "M6 7l5 5-5 5")
    val Audio = stroke("audio", "M4 9.5v5h3.5L12 18.5v-13L7.5 9.5z", "M15.5 9a4 4 0 0 1 0 6", "M18 6.5a7.6 7.6 0 0 1 0 11")
    val Subtitles = stroke("subtitles", "M3.5 5.5h17v13h-17z", "M7 14.5h4", "M13.5 14.5H17", "M7 10.5h7", "M16.5 10.5H17")
    val Check = stroke("check", "M5 12.5l4.5 4.5L19 7.5")
    val Plus = stroke("plus", "M12 5v14", "M5 12h14")
    val Back = stroke("back", "M15 5l-7 7 7 7")
    val Refresh = stroke("refresh", "M19.5 12a7.5 7.5 0 1 1-2.2-5.3", "M19.5 4.5v4.5H15")
    val Next = fill("next", "M6 5.6v12.8a.6.6 0 0 0 .9.5l9.4-6.4a.6.6 0 0 0 0-1L6.9 5.1a.6.6 0 0 0-.9.5z", "M17 5h2v14h-2z")
    val Star = fill("star", "M12 3.8l2.5 5.2 5.7.8-4.1 4 1 5.7-5.1-2.7-5.1 2.7 1-5.7-4.1-4 5.7-.8z")
    val Filter = stroke("filter", "M4 6h16", "M7 12h10", "M10 18h4")
    val Signal = stroke("signal", "M12 13.5a1.5 1.5 0 1 0 0-.01", "M8.5 9.5a5 5 0 0 0 0 7", "M15.5 9.5a5 5 0 0 1 0 7", "M5.8 6.8a9 9 0 0 0 0 12.4", "M18.2 6.8a9 9 0 0 1 0 12.4")
}
