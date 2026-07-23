package ymusic.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import org.jetbrains.skia.EncodedImageFormat
import ymusic.dynamicengine.yt.YtFeedItem
import ymusic.ui.FeedGridCard
import ymusic.ui.ItemActions
import ymusic.ui.YMusicTheme
import java.io.File
import kotlin.test.Test

/** Dev harness, not an assertion test: renders grid cards headlessly to a PNG for visual checks. */
class GridCardSnapshotTest {
    @Test
    fun renderVideoAndPlaylistCards() {
        val video = YtFeedItem(
            kind = YtFeedItem.KIND_VIDEO,
            id = "v",
            title = "Video title",
            subtitleLine1 = "Channel",
            subtitleLine2 = "1M views",
            duration = "4:39",
        )
        val playlist = YtFeedItem(
            kind = YtFeedItem.KIND_PLAYLIST,
            id = "p",
            title = "Mix — Sơn Tùng M-TP",
            subtitleLine1 = "Sơn Tùng M-TP, JustaTee",
            itemCount = "42",
        )
        val scene = ImageComposeScene(width = 600, height = 260) {
            YMusicTheme(dark = false) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    FeedGridCard(video, ItemActions(emptyList()), onClick = {}, modifier = Modifier.weight(1f))
                    FeedGridCard(playlist, ItemActions(emptyList()), onClick = {}, modifier = Modifier.weight(1f))
                }
            }
        }
        scene.use {
            val png = it.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
            val out = File(System.getProperty("snapshotOut") ?: "build/grid-cards.png")
            out.parentFile?.mkdirs()
            out.writeBytes(png)
            println("snapshot: ${out.absolutePath}")
        }
    }
}
