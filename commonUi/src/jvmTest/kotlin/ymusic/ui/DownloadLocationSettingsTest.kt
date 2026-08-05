package ymusic.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
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
import ymusic.settings.put
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

    private fun engines() =
        DynamicEngineManager(CannedHttp { _, _ -> throw UnsupportedOperationException() }, appVersion = 1)

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
                // No dialog — the picker ran, set the location, and we land on the format dialog.
                waitUntilAtLeastOneExists(hasText("Audio · M4A"), 5_000)
                assertEquals("file:///Picked", settings.flow(AppSettings.downloadLocation).value)
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
            engines = engines(),
            player = StubPlayer(),
            playerSurface = {},
            mediaStore = store,
            settings = settings,
            downloads = downloadService(scope),
        )
        try {
            runComposeUiTest {
                setContent { DefaultDownloadLocationPrompt(graph) }

                runBlocking { store.upsertBucket(BucketDraft(Uri.parse("file:///Library"), "Library")) }
                waitUntilAtLeastOneExists(hasText("Set as default"), 5_000)

                onNodeWithText("Set as default").performClick()
                waitUntil(timeoutMillis = 5_000) { settings.flow(AppSettings.downloadLocation).value == "file:///Library" }
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
                // The stale location is invalid → picker runs → new folder adopted → format dialog.
                waitUntilAtLeastOneExists(hasText("Audio · M4A"), 5_000)
                assertEquals("file:///New", settings.flow(AppSettings.downloadLocation).value)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun library_folder_menu_toggles_the_download_location() {
        Locale.setDefault(Locale.ENGLISH)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engines = engines()
        runBlocking { engines.start() }
        val store = BetterMediaStore.create(mediaStoreJdbcDriver(":memory:"))
        runBlocking { store.upsertBucket(BucketDraft(Uri.parse("file:///Music"), "Music")) }
        val settings = InMemorySettingsStore()
        val graph = AppGraph(
            engines = engines,
            player = StubPlayer(),
            playerSurface = {},
            mediaStore = store,
            settings = settings,
            downloads = downloadService(scope),
        )
        try {
            runComposeUiTest {
                setContent { App(graph, mutableStateOf(AppSection.Library)) }
                waitUntilAtLeastOneExists(hasText("Folders"), 20_000)
                onNodeWithText("Folders").performClick()
                waitUntilAtLeastOneExists(hasText("Music"), 5_000) // the sole folder row

                onNodeWithContentDescription("More").performClick()
                onNodeWithText("Download location").performClick()
                waitUntil(timeoutMillis = 5_000) { settings.flow(AppSettings.downloadLocation).value == "file:///Music" }

                // Toggle it off again.
                onNodeWithContentDescription("More").performClick()
                onNodeWithText("Download location").performClick()
                waitUntil(timeoutMillis = 5_000) { settings.flow(AppSettings.downloadLocation).value.isBlank() }
            }
        } finally {
            runBlocking { engines.close() }
            scope.cancel()
        }
    }
}
