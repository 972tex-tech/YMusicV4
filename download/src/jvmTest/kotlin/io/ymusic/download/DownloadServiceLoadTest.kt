package io.ymusic.download

import kmphttp.KmpHttpClient
import kmphttp.create
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** On startup the queue does not auto-start; anything in-flight or queued (e.g. after a crash) is paused. */
class DownloadServiceLoadTest {

    private object ThrowingStorage : DownloadStorage {
        override fun scratch(taskId: String, name: String): DownloadScratch = throw UnsupportedOperationException()
        override fun deleteScratch(taskId: String) = Unit
        override suspend fun publish(scratch: DownloadScratch, fileName: String, destinationFolder: String): String = throw UnsupportedOperationException()
    }

    private fun task(id: String, state: DownloadState) = DownloadTask(
        id = id, videoId = "v$id", title = id, artist = null, thumbnailUrl = null,
        format = DownloadFormat.M4A, requestedHeight = null, state = state,
        downloadedBytes = 0, totalBytes = 0, fileUri = null, errorMessage = null,
        createdAt = 100, updatedAt = 100,
    )

    @Test
    fun init_pauses_in_flight_and_queued_keeps_terminal_and_failed() = runBlocking {
        val store = DownloadStore(downloadDatabaseJdbcDriver())
        listOf(
            task("prep", DownloadState.PREPARING),
            task("dl", DownloadState.DOWNLOADING),
            task("proc", DownloadState.PROCESSING),
            task("queued", DownloadState.QUEUED),
            task("done", DownloadState.COMPLETED),
            task("failed", DownloadState.FAILED),
            task("paused", DownloadState.PAUSED),
        ).forEach { store.upsert(it) }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = DownloadService(
            store = store, scope = scope, now = { 0L },
            resolver = SabrSpecResolver { throw UnsupportedOperationException() },
            client = KmpHttpClient.create {}, userAgent = "test", ffmpeg = null, storage = ThrowingStorage,
            downloadFolder = { "file:///tmp/dl" },
        )
        try {
            service.initIfNotYet()
            val state = store.all().associate { it.id to it.state }
            // in-flight + queued → paused (no auto-start)
            assertEquals(DownloadState.PAUSED, state["prep"])
            assertEquals(DownloadState.PAUSED, state["dl"])
            assertEquals(DownloadState.PAUSED, state["proc"])
            assertEquals(DownloadState.PAUSED, state["queued"])
            // terminal / failed / already-paused kept
            assertEquals(DownloadState.COMPLETED, state["done"])
            assertEquals(DownloadState.FAILED, state["failed"])
            assertEquals(DownloadState.PAUSED, state["paused"])
        } finally {
            scope.cancel()
        }
    }
}
