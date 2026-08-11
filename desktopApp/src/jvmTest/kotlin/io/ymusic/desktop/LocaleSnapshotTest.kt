package io.ymusic.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.dynamicengine.yt.YtSectionHeader
import io.ymusic.ui.AppDrawer
import io.ymusic.ui.AppSection
import io.ymusic.ui.FeedListItem
import io.ymusic.ui.ItemActions
import io.ymusic.ui.SectionHeader
import io.ymusic.ui.ShowMoreButton
import io.ymusic.ui.YMusicTheme
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.library_empty
import io.ymusic.ui.resources.show_more_n
import io.ymusic.ui.resources.up_next
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.util.Locale
import kotlin.test.Test

/** Dev harness, not an assertion test: renders localized components per locale (ar = RTL) to PNGs. */
class LocaleSnapshotTest {
    @Test
    fun renderLocales() {
        val original = Locale.getDefault()
        try {
            listOf("en" to LayoutDirection.Ltr, "vi" to LayoutDirection.Ltr, "ar" to LayoutDirection.Rtl)
                .forEach { (tag, direction) -> render(tag, direction) }
        } finally {
            Locale.setDefault(original)
        }
    }

    private fun render(tag: String, direction: LayoutDirection) {
        Locale.setDefault(Locale.forLanguageTag(tag))
        val item = YtFeedItem(
            kind = YtFeedItem.KIND_VIDEO,
            id = "v",
            title = "Sơn Tùng M-TP — Chúng Ta Của Hiện Tại",
            subtitleLine1 = "Sơn Tùng M-TP",
            subtitleLine2 = "126M views",
            duration = "4:39",
            live = true,
        )
        val scene = ImageComposeScene(width = 420, height = 760) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                YMusicTheme(dark = false) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.height(400.dp).clipToBounds()) {
                            AppDrawer(
                                section = AppSection.Library,
                                appVersion = "locale harness",
                                onSelect = {},
                            )
                        }
                        SectionHeader(YtSectionHeader(title = "…", count = "12"), collapsed = true, onToggle = {})
                        FeedListItem(item, ItemActions(emptyList()), onClick = {})
                        ShowMoreButton(stringResource(Res.string.show_more_n, 7), expanded = false, onClick = {})
                        Text(stringResource(Res.string.library_empty), Modifier.padding(16.dp), fontSize = 13.sp)
                        Text(stringResource(Res.string.up_next), Modifier.padding(horizontal = 16.dp), fontSize = 13.sp)
                    }
                }
            }
        }
        scene.use {
            val png = it.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
            val out = File("build/locale-$tag.png")
            out.parentFile?.mkdirs()
            out.writeBytes(png)
            println("snapshot: ${out.absolutePath}")
        }
    }
}
