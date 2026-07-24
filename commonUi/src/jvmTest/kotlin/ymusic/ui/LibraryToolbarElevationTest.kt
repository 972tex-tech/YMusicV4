package ymusic.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import kmpcommon.uri.Uri
import kotlinx.coroutines.runBlocking
import ymusic.dynamicengine.DynamicEngineManager
import ymusic.mediastore.BetterMediaStore
import ymusic.mediastore.mediaStoreJdbcDriver
import ymusic.mediastore.model.MediaItemDraft
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Library reports its scroll to the shell's ScrollHost (which lifts the app bar); like the
 * Discover feed, its lists must flag "scrolled" once content moves under the bar and clear it at the
 * top. Regression: the Library never reported its scroll, so the bar stayed flat.
 */
@OptIn(ExperimentalTestApi::class)
class LibraryToolbarElevationTest {

    @Test
    fun scrolling_the_song_list_reports_scroll_to_the_shell() {
        val client = CannedHttp { request, _ -> textResponse(request, "{}", "application/json") }
        val engines = DynamicEngineManager(client, appVersion = 1)
        val store = BetterMediaStore.create(mediaStoreJdbcDriver(":memory:"))
        val songs = runBlocking {
            repeat(60) { i ->
                store.upsertItem(
                    bucketId = null,
                    MediaItemDraft(
                        uri = Uri.parse("file:///m/s$i.mp3"),
                        title = "Song $i",
                        mimeType = "audio/mpeg",
                        fileSize = (i + 1).toLong(),
                        lastModified = 1_000L,
                    ),
                )
            }
            store.allItems()
        }
        val host = ScrollHost()
        try {
            runComposeUiTest {
                setContent {
                    val coordinator = PlaybackCoordinator(
                        AppGraph(engines = engines, player = StubPlayer(), playerSurface = {}, mediaStore = store),
                        rememberCoroutineScope(),
                    )
                    CompositionLocalProvider(LocalScrollHost provides host) {
                        SongList(songs, coordinator)
                    }
                }

                waitForIdle()
                assertFalse(host.scrolled, "flat at the top")

                onNode(hasScrollAction()).performScrollToIndex(40)
                waitUntil(timeoutMillis = 5_000L) { host.scrolled }
                assertTrue(host.scrolled, "reports scrolled after scrolling away from the top")
            }
        } finally {
            runBlocking { engines.close() }
        }
    }
}
