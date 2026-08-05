package ymusic.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A change the [DownloadService] made to a task, published so the on-demand [DownloadTasksAggregator]
 *  can patch its view without re-reading the whole queue. The DB write always precedes the event. */
sealed interface DownloadChange {
    data class Added(val task: DownloadTask) : DownloadChange
    data class StateChanged(val id: String, val state: DownloadState, val error: String?) : DownloadChange
    data class ProgressChanged(val id: String, val downloaded: Long, val total: Long) : DownloadChange
    data class Completed(val id: String, val fileUri: String, val bytes: Long) : DownloadChange
    data class Removed(val id: String) : DownloadChange
}

/**
 * Materializes the download queue as a list **only while someone (the UI) is watching**. On the first
 * collector it queries all tasks once, then folds in [DownloadChange] events to stay current without
 * re-reading the DB; when the last collector leaves it drops the list. The [DownloadService] never holds
 * the queue resident — the DB is the source of truth and this is a lazy, UI-scoped read model.
 */
class DownloadTasksAggregator(
    private val store: DownloadStore,
    private val changes: SharedFlow<DownloadChange>,
    scope: CoroutineScope,
) {
    val tasks: StateFlow<List<DownloadTask>> = channelFlow {
        val byId = LinkedHashMap<String, DownloadTask>()
        val gate = Mutex() // serialize the one-time seed against incoming events
        fun snapshot() = byId.values.sortedByDescending { it.createdAt } // newest first

        // Subscribe before seeding so no event is lost in the gap; the seed reads the DB (authoritative,
        // written before every event), so an event that raced ahead is reconciled either way.
        changes.onEach { change ->
            gate.withLock {
                apply(byId, change)
                send(snapshot())
            }
        }.launchIn(this)

        gate.withLock {
            store.all().forEach { byId[it.id] = it }
            send(snapshot())
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 2_000), emptyList())

    private fun apply(byId: MutableMap<String, DownloadTask>, change: DownloadChange) {
        when (change) {
            is DownloadChange.Added -> byId[change.task.id] = change.task
            is DownloadChange.Removed -> byId.remove(change.id)
            is DownloadChange.StateChanged ->
                byId[change.id]?.let { byId[change.id] = it.copy(state = change.state, errorMessage = change.error) }
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
                    )
                }
        }
    }
}
