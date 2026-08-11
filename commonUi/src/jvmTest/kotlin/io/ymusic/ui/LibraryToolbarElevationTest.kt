package io.ymusic.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.mediastore.BetterMediaStore
import io.ymusic.mediastore.mediaStoreJdbcDriver
import io.ymusic.mediastore.model.MediaItemDraft
import kmpcommon.uri.Uri
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Library's app bar + tab row are one pinned, elevated surface; scrolling a Library list reports
 * to the shell's ScrollHost so they lift together (the sort scrolls away as content). Regression: the
 * Library never reported its scroll, so the bar stayed flat.
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
                assertTrue(host.scrolled, "app bar + tabs lift after scrolling away from the top")
            }
        } finally {
            runBlocking { engines.close() }
        }
    }
}
