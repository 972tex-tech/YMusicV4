package io.ymusic.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Telegram's paper plane (SVG Repo). One colour: it takes the tint of whatever draws it. */
internal val TelegramIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Telegram",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 100f,
        viewportHeight = 100f,
    ).addPath(
        pathData = addPathNodes(
            "M88.723,12.142C76.419,17.238,23.661,39.091,9.084,45.047c-9.776,3.815-4.053,7.392-4.053,7.392" +
                "s8.345,2.861,15.499,5.007c7.153,2.146,10.968-0.238,10.968-0.238l33.62-22.652c11.922-8.107,9.061-1.431," +
                "6.199,1.431c-6.199,6.2-16.452,15.975-25.036,23.844c-3.815,3.338-1.908,6.199-0.238,7.63" +
                "c6.199,5.246,23.129,15.976,24.082,16.691c5.037,3.566,14.945,8.699,16.452-2.146" +
                "c0,0,5.961-37.435,5.961-37.435c1.908-12.637,3.815-24.321,4.053-27.659C97.307,8.804,88.723,12.142,88.723,12.142z",
        ),
        fill = SolidColor(Color.Black),
    ).build()
}
