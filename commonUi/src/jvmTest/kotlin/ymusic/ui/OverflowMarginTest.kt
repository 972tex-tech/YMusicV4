package ymusic.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kmpcommon.uri.Uri
import ymusic.dynamicengine.DynamicEngineManager
import ymusic.dynamicengine.yt.YtFeedItem
import ymusic.mediastore.model.MediaStoreItem
import java.util.Locale
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A feed row's ⋮ and a Library song row's ⋮ must sit at the same distance from the edge — they are
 * the same control in two lists, and the app looks broken when they disagree.
 */
@OptIn(ExperimentalTestApi::class)
class OverflowMarginTest {

    @Test
    fun the_feed_and_library_overflow_dots_share_one_trailing_margin() {
        Locale.setDefault(Locale.ENGLISH)
        val item = YtFeedItem(kind = YtFeedItem.KIND_VIDEO, id = "v", title = "A video", subtitleLine1 = "A channel")
        val song = MediaStoreItem(
            id = 1L,
            bucketId = null,
            uri = Uri.parse("file:///song.mp3"),
            title = "A song",
            mimeType = "audio/mpeg",
            artist = "An artist",
            album = null,
            genre = null,
            trackNumber = null,
            year = null,
            durationMs = 279_000L,
            audioCodec = null,
            videoCodec = null,
            subtitleCodec = null,
            bitrate = null,
            width = null,
            height = null,
            thumbnailStreamIndex = null,
            youtubeVid = null,
            youtubeItags = null,
            fileSize = 1L,
            lastModified = 0L,
            createdAt = 0L,
        )
        runComposeUiTest {
            setContent {
                val scope = rememberCoroutineScope()
                val graph = AppGraph(
                    engines = DynamicEngineManager(CannedHttp { request, _ -> textResponse(request, "{}", "application/json") }, appVersion = 1),
                    player = StubPlayer(),
                    playerSurface = {},
                )
                YMusicTheme(dark = false) {
                    Column(Modifier.width(400.dp)) {
                        FeedListItem(item, ItemActions(listOf("Play" to {})), onClick = {})
                        SongRow(song, PlaybackCoordinator(graph, scope)) {}
                    }
                }
            }

            val dots = onAllNodesWithContentDescription("More").fetchSemanticsNodes()
            assertEquals(2, dots.size, "one ⋮ per row")
            val (feed, library) = dots.map { it.boundsInRoot.right }
            assertTrue(abs(feed - library) < 0.5f, "feed ⋮ right edge $feed, library ⋮ right edge $library")
        }
    }
}
