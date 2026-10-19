package io.ymusic.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.ymusic.download.DownloadScratch
import io.ymusic.download.DownloadService
import io.ymusic.download.DownloadStorage
import io.ymusic.download.DownloadStore
import io.ymusic.download.SabrSpecResolver
import io.ymusic.download.downloadDatabaseJdbcDriver
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.embeddedEngineJsSource
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.mediastore.BetterMediaStore
import io.ymusic.mediastore.mediaStoreJdbcDriver
import io.ymusic.mediastore.model.BucketDraft
import io.ymusic.settings.AppSettings
import io.ymusic.settings.InMemorySettingsStore
import io.ymusic.settings.flow
import io.ymusic.settings.put
import kmpcommon.uri.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The download-location UX: with none set, tapping Download goes straight to the folder picker (which
 * adds the folder to the Library and adopts it), and that folder can be re-designated / cleared from the
 * Library tab. Removing (or excluding) the folder clears the location. Drives the real settings + store.
 */
@OptIn(ExperimentalTestApi::class)
class DownloadLocationSettingsTest {

    private object NoopStorage : DownloadStorage {
        override fun scratch(taskId: String, name: String): DownloadScratch = throw UnsupportedOperationException()
        override fun deleteScratch(taskId: String) = Unit
        override suspend fun publish(scratch: DownloadScratch, fileName: String, destinationFolder: String): String = throw UnsupportedOperationException()
    }

    private fun downloadService(scope: CoroutineScope) = DownloadService(
        store = DownloadStore(downloadDatabaseJdbcDriver()),
        scope = scope,
        now = { 0L },
        resolver = SabrSpecResolver { throw UnsupportedOperationException() },
        client = CannedHttp { _, _ -> throw UnsupportedOperationException() },
        userAgent = "test",
        ffmpeg = null,
        storage = NoopStorage,
        downloadFolder = { "file:///tmp/dl" },
    )

    private fun engines() =
        DynamicEngineManager(CannedHttp { _, _ -> throw UnsupportedOperationException() }, appVersion = 1, jsSource = embeddedEngineJsSource())

    @Test
    fun download_with_no_location_goes_straight_to_the_folder_picker() {
        Locale.setDefault(Locale.ENGLISH)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = BetterMediaStore.create(mediaStoreJdbcDriver(":memory:"))
        val settings = InMemorySettingsStore()
        val graph = AppGraph(
            engines = engines(),
            player = StubPlayer(),
            playerSurface = {},
            mediaStore = store,
            settings = settings,
            downloads = downloadService(scope),
            // The folder picker — pretend the user picked a folder; it's registered and its uri returned.
            libraryFolderPicker = {
                store.upsertBucket(BucketDraft(Uri.parse("file:///Picked"), "Picked")).uri.toString()
            },
        )
        try {
            runComposeUiTest {
                val video = YtFeedItem(kind = YtFeedItem.KIND_VIDEO, id = "abc", title = "A Song")
                setContent {
                    CompositionLocalProvider(LocalSnackbar provides {}) {
                        DownloadDialogHost(graph, video) {}
                    }
                }
                // No dialog — the picker ran, set the location, and we land on the download options dialog.
                waitUntilAtLeastOneExists(hasText("File name"), 5_000)
                assertEquals("file:///Picked", settings.flow(AppSettings.downloadLocation).value)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun download_reprompts_when_the_saved_location_folder_is_gone() {
        Locale.setDefault(Locale.ENGLISH)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = BetterMediaStore.create(mediaStoreJdbcDriver(":memory:"))
        val settings = InMemorySettingsStore()
        // A location was set, but its folder has since been removed from the Library.
        runBlocking {
            val old = store.upsertBucket(BucketDraft(Uri.parse("file:///Old"), "Old"))
            settings.put(AppSettings.downloadLocation, old.uri.toString())
            store.deleteBucket(old.id)
        }
        val graph = AppGraph(
            engines = engines(),
            player = StubPlayer(),
            playerSurface = {},
            mediaStore = store,
            settings = settings,
            downloads = downloadService(scope),
            libraryFolderPicker = {
                store.upsertBucket(BucketDraft(Uri.parse("file:///New"), "New")).uri.toString()
            },
        )
        try {
            runComposeUiTest {
                val video = YtFeedItem(kind = YtFeedItem.KIND_VIDEO, id = "abc", title = "A Song")
                setContent {
                    CompositionLocalProvider(LocalSnackbar provides {}) {
                        DownloadDialogHost(graph, video) {}
                    }
                }
                // The stale location is invalid → picker runs → new folder adopted → download options dialog.
                waitUntilAtLeastOneExists(hasText("File name"), 5_000)
                assertEquals("file:///New", settings.flow(AppSettings.downloadLocation).value)
            }
        } finally {
            scope.cancel()
        }
    }
}
