package io.ymusic.download

import kmphttp.KmpHttpClient
import kmphttp.create
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadServiceQueueTest {

    // Every scratch reads as empty (no ready output from an earlier run); anything else is unexpected.
    private object EmptyScratch : DownloadScratch {
        override val path: String get() = throw UnsupportedOperationException()
        override fun length(): Long = 0
        override fun appendSink(): okio.BufferedSink = throw UnsupportedOperationException()
        override fun source(): okio.Source = throw UnsupportedOperationException()
        override fun delete() = Unit
        override fun close() = Unit
    }

    private object ThrowingStorage : DownloadStorage {
        override fun scratch(taskId: String, name: String): DownloadScratch = EmptyScratch
        override fun deleteScratch(taskId: String) = Unit
        override suspend fun publish(scratch: DownloadScratch, fileName: String, destinationFolder: String): String = throw UnsupportedOperationException()
    }

    private fun task(id: String, state: DownloadState) = DownloadTask(
        id = id, videoId = "v$id", title = id, artist = null, thumbnailUrl = null,
        format = DownloadFormat.M4A, requestedHeight = null, state = state,
        downloadedBytes = 0, totalBytes = 0, fileUri = null, errorMessage = null,
        createdAt = 0, updatedAt = 0,
    )

    // A resolver that never returns — a started task parks in PREPARING, holding its slot.
    private fun service(store: DownloadStore, scope: CoroutineScope, maxConcurrent: Int) = DownloadService(
        store = store, scope = scope, now = { 0L },
        resolver = SabrSpecResolver { awaitCancellation() },
        client = KmpHttpClient.create {}, userAgent = "test", ffmpeg = null,
        storage = ThrowingStorage, downloadFolder = { "file:///tmp/dl" }, maxConcurrent = maxConcurrent,
    )

    @Test
    fun runs_at_most_max_concurrent() = runBlocking {
        val store = DownloadStore(downloadDatabaseJdbcDriver())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = service(store, scope, maxConcurrent = 2)
        try {
            repeat(5) { service.enqueue(DownloadRequest(videoId = "v$it", title = "t$it", format = DownloadFormat.M4A)) }
            withTimeout(5_000) {
                while (store.all().count { it.state == DownloadState.PREPARING } < 2) delay(20)
            }
            delay(200) // give any over-fill a chance to (wrongly) start
            val counts = store.all().groupingBy { it.state }.eachCount()
            assertEquals(2, counts[DownloadState.PREPARING] ?: 0) // exactly the slot limit runs
            assertEquals(3, counts[DownloadState.QUEUED] ?: 0) // the rest wait
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun pause_all_holds_every_queued_task() = runBlocking {
        val store = DownloadStore(downloadDatabaseJdbcDriver())
        listOf("a", "b", "c").forEach { store.upsert(task(it, DownloadState.QUEUED)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = service(store, scope, maxConcurrent = 3)
        try {
            service.pauseAll()
            withTimeout(5_000) { while (store.all().any { it.state == DownloadState.QUEUED }) delay(20) }
            assertTrue(store.all().all { it.state == DownloadState.PAUSED })
        } finally {
            scope.cancel()
        }
    }
}
