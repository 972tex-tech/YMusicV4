package io.ymusic.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** The aggregator seeds the queue once from its seed source, then folds change events to stay current. */
class DownloadTasksAggregatorTest {

    private fun task(id: String, state: DownloadState = DownloadState.QUEUED) = DownloadTask(
        id = id, videoId = "v$id", title = id, artist = null, thumbnailUrl = null,
        format = DownloadFormat.M4A, requestedHeight = null, state = state,
        downloadedBytes = 0, totalBytes = 0, fileUri = null, errorMessage = null,
        createdAt = id.first().code.toLong(), updatedAt = 0, // createdAt from the letter → stable ordering
    )

    @Test
    fun seeds_then_folds_add_progress_state_and_remove() = runBlocking {
        val seedTasks = mutableListOf(task("a"))
        val changes = MutableSharedFlow<DownloadChange>(extraBufferCapacity = 64)
        val agg = DownloadTasksAggregator(seed = { seedTasks.toList() }, changes = changes)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // Hot-share the cold flow so we can await successive values (mimics collectAsState keeping it live).
        val tasks = agg.tasks.stateIn(scope, SharingStarted.Eagerly, emptyList())
        try {
            withTimeout(5_000) { tasks.first { it.map { t -> t.id } == listOf("a") } }
            // The seed emission doesn't imply the change collector has subscribed yet, and a
            // replay-0 SharedFlow drops subscriber-less emissions. Production tolerates that gap
            // (the seed re-reads the store); this static-seed test must not emit into it.
            withTimeout(5_000) { changes.subscriptionCount.first { it > 0 } }

            changes.emit(DownloadChange.Added(task("b")))
            withTimeout(5_000) { tasks.first { it.map { t -> t.id }.toSet() == setOf("a", "b") } }

            changes.emit(DownloadChange.ProgressChanged("a", downloaded = 50, total = 100))
            withTimeout(5_000) { tasks.first { it.firstOrNull { t -> t.id == "a" }?.progress == 0.5f } }

            changes.emit(DownloadChange.StateChanged("b", DownloadState.FAILED, DownloadError("E70001", null)))
            withTimeout(5_000) { tasks.first { it.firstOrNull { t -> t.id == "b" }?.state == DownloadState.FAILED } }

            changes.emit(DownloadChange.Removed("b"))
            withTimeout(5_000) { tasks.first { it.map { t -> t.id } == listOf("a") } }

            assertEquals(0.5f, tasks.value.first { it.id == "a" }.progress)
        } finally {
            scope.cancel()
        }
    }
}
