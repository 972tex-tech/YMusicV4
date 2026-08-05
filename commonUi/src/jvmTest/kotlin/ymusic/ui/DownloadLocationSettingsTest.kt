package ymusic.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import kmpcommon.uri.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import ymusic.download.DownloadScratch
import ymusic.download.DownloadService
import ymusic.download.DownloadStorage
import ymusic.download.DownloadStore
import ymusic.download.SabrSpecResolver
import ymusic.download.downloadDatabaseJdbcDriver
import ymusic.dynamicengine.DynamicEngineManager
import ymusic.dynamicengine.yt.YtFeedItem
import ymusic.mediastore.BetterMediaStore
import ymusic.mediastore.mediaStoreJdbcDriver
import ymusic.mediastore.model.BucketDraft
import ymusic.settings.AppSettings
import ymusic.settings.InMemorySettingsStore
import ymusic.settings.flow
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The download-location UX: tapping Download with no location set gates on a folder pick (choosing one
 * advances to the format dialog, it does not cancel the download), and adding the first Library folder
 * offers to make it the default download location. Both drive the real settings + media store.
 */
@OptIn(ExperimentalTestApi::class)
class DownloadLocationSettingsTest {

    private object NoopStorage : DownloadStorage {
        override fun scratch(name: String): DownloadScratch = throw UnsupportedOperationException()
        override suspend fun publish(scratch: DownloadScratch, fileName: String): String = throw UnsupportedOperationException()
    }

    private fun downloadService(scope: CoroutineScope) = DownloadService(
        store = DownloadStore(downloadDatabaseJdbcDriver()),
        scope = scope,
        now = { 0L },
        resolver = SabrSpecResolver { throw UnsupportedOperationException() },
        client = CannedHttp { _, _ -> throw UnsupportedOperationException() },
        userAgent = "test",
        ffmpeg = null, // no ffmpeg → the format dialog shows only the M4A option
        storage = NoopStorage,
    )

    @Test
    fun download_with_no_location_gates_on_a_folder_pick_then_shows_formats() {
        Locale.setDefault(Locale.ENGLISH)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = BetterMediaStore.create(mediaStoreJdbcDriver(":memory:"))
        runBlocking { store.upsertBucket(BucketDraft(Uri.parse("file:///Music"), "Music")) }
        val settings = InMemorySettingsStore()
        val graph = AppGraph(
            engines = DynamicEngineManager(CannedHttp { _, _ -> throw UnsupportedOperationException() }, appVersion = 1),
            player = StubPlayer(),
            playerSurface = {},
            mediaStore = store,
            settings = settings,
            downloads = downloadService(scope),
        )
        try {
            runComposeUiTest {
                val video = YtFeedItem(kind = YtFeedItem.KIND_VIDEO, id = "abc", title = "A Song")
                setContent {
                    CompositionLocalProvider(LocalSnackbar provides {}) {
                        DownloadDialogHost(graph, video) {}
                    }
                }

                // Location unset → the picker gate, not the format dialog.
                waitUntilAtLeastOneExists(hasText("Download location"), 5_000)
                onNodeWithText("Music").performClick()

                // Picking a folder advances to the format dialog (M4A only, no ffmpeg).
                waitUntilAtLeastOneExists(hasText("Audio · M4A"), 5_000)
                assertEquals("file:///Music", settings.flow(AppSettings.downloadLocation).value)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun adding_the_first_folder_offers_it_as_the_default_location() {
        Locale.setDefault(Locale.ENGLISH)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = BetterMediaStore.create(mediaStoreJdbcDriver(":memory:"))
        val settings = InMemorySettingsStore()
        val graph = AppGraph(
            engines = DynamicEngineManager(CannedHttp { _, _ -> throw UnsupportedOperationException() }, appVersion = 1),
            player = StubPlayer(),
            playerSurface = {},
            mediaStore = store,
            settings = settings,
            downloads = downloadService(scope),
        )
        try {
            runComposeUiTest {
                setContent { DefaultDownloadLocationPrompt(graph) }

                // First folder appears → the offer dialog.
                runBlocking { store.upsertBucket(BucketDraft(Uri.parse("file:///Library"), "Library")) }
                waitUntilAtLeastOneExists(hasText("Set as default"), 5_000)

                onNodeWithText("Set as default").performClick()
                waitUntilDoesNotExist(hasText("Set as default"), 5_000) // dialog gone once saved
                assertEquals("file:///Library", settings.flow(AppSettings.downloadLocation).value)
            }
        } finally {
            scope.cancel()
        }
    }
}
