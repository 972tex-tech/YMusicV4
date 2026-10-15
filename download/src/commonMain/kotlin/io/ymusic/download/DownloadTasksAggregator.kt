package io.ymusic.download

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A change the [DownloadService] made to a task, published so a [DownloadTasksAggregator] can patch its
 *  view without re-reading the whole queue. The DB write always precedes the event. */
sealed interface DownloadChange {
    data class Added(val task: DownloadTask) : DownloadChange
    data class StateChanged(val id: String, val state: DownloadState, val error: DownloadError?) : DownloadChange
    data class ProgressChanged(val id: String, val downloaded: Long, val total: Long) : DownloadChange
    data class Completed(val id: String, val fileUri: String, val bytes: Long) : DownloadChange

    data class FormatChanged(val id: String, val format: DownloadFormat) : DownloadChange
    data class Removed(val id: String) : DownloadChange
}

/**
 * Materializes the download queue as a list for a UI. The [DownloadService] holds no list — it just
 * dispatches [DownloadChange] events; the UI creates one of these when the Downloads screen appears and
 * discards it on leave. [tasks] is a cold flow: on collection it [seed]s the queue once, then folds in
 * change events; when collection stops (screen closed) it drops everything. Own it with `remember` +
 * `collectAsState` so its lifetime is the composition's.
 */
class DownloadTasksAggregator(
    private val seed: suspend () -> List<DownloadTask>,
    private val changes: SharedFlow<DownloadChange>,
) {
    val tasks: Flow<List<DownloadTask>> = channelFlow {
        val byId = LinkedHashMap<String, DownloadTask>()
        val gate = Mutex() // serialize the one-time seed against incoming events

        // Subscribe before seeding so no event is lost in the gap; the seed reads the store (authoritative,
        // written before every event), so an event that raced ahead is reconciled either way.
        changes.onEach { change ->
            gate.withLock {
                apply(byId, change)
                send(byId.values.sortedByDescending { it.createdAt }) // newest first
            }
        }.launchIn(this)

        gate.withLock {
            seed().forEach { byId[it.id] = it }
            send(byId.values.sortedByDescending { it.createdAt })
        }
    }

    private fun apply(byId: MutableMap<String, DownloadTask>, change: DownloadChange) {
        when (change) {
            is DownloadChange.Added -> byId[change.task.id] = change.task
            is DownloadChange.Removed -> byId.remove(change.id)
            is DownloadChange.StateChanged ->
                byId[change.id]?.let {
                    byId[change.id] = it.copy(state = change.state, errorMessage = change.error?.userMessage, errorCode = change.error?.code)
                }
            is DownloadChange.FormatChanged -> byId[change.id]?.let { byId[change.id] = it.copy(format = change.format) }
            is DownloadChange.ProgressChanged ->
                byId[change.id]?.let { byId[change.id] = it.copy(downloadedBytes = change.downloaded, totalBytes = change.total) }
            is DownloadChange.Completed ->
                byId[change.id]?.let {
                    byId[change.id] = it.copy(
                        state = DownloadState.COMPLETED,
                        fileUri = change.fileUri,
                        downloadedBytes = change.bytes,
                        totalBytes = change.bytes,
                        errorMessage = null,
                        errorCode = null,
                    )
                }
        }
    }
}
