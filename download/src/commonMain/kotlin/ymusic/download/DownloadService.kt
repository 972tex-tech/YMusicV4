package ymusic.download

import kmpcommon.error.codedError
import kmpcommon.log.Logger
import kmphttp.KmpHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ymusic.ytmedia.cache.MediaCache
import kotlin.random.Random

/**
 * Manages the download queue. The DB ([store]) is the single source of truth — the service holds no task
 * list, only the one active worker slot; the UI reads via [tasks] (a lazy [DownloadTasksAggregator]).
 *
 * Queue model: one task runs at a time. Adding a task starts it if nothing is active; when a task
 * finishes (or is canceled), the next queued one starts. **Pause stops the whole queue** — the active
 * download is interrupted and every queued task is held; the user then resumes tasks individually.
 * Nothing auto-starts on [load] (a crash leaves tasks PAUSED).
 *
 * Pause and cancel both cancel the active [Job]; the difference is the recorded state and whether the
 * partially-downloaded files are kept (pause, for resume via the scratch length) or deleted (cancel).
 * Control methods are fire-and-forget — they launch into [scope].
 */
class DownloadService(
    private val store: DownloadStore,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    resolver: SabrSpecResolver,
    client: KmpHttpClient,
    userAgent: String,
    ffmpeg: Ffmpeg?,
    private val storage: DownloadStorage,
    mediaCache: MediaCache? = null,
    private val logger: Logger? = null,
    /** Current caption selection, read fresh per download (the shell sources it from settings). Keeps the
     *  service headless and avoids persisting the preference on each task. Defaults to no captions. */
    private val captionPreference: suspend () -> CaptionPreference = { CaptionPreference.None },
    /** Optional hook to register a finished file into the local library (media-store); shell-provided. */
    private val onCompleted: (suspend (DownloadTask, DownloadPipeline.Result) -> Unit)? = null,
) {
    /** True where ffmpeg exists (Android/desktop). False on iOS — the UI hides MP3/video outputs there. */
    val ffmpegAvailable: Boolean = ffmpeg != null

    private val pipeline = DownloadPipeline(
        resolver, client, userAgent, ffmpeg, storage, mediaCache, logger,
    )

    private val changes = MutableSharedFlow<DownloadChange>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** The live queue for the UI — materialized only while collected (see [DownloadTasksAggregator]). */
    val tasks: StateFlow<List<DownloadTask>> = DownloadTasksAggregator(store, changes, scope).tasks

    // The single worker slot. `gate` guards it and the interrupt reason for the active task.
    private val gate = Mutex()
    private var activeId: String? = null
    private var activeJob: Job? = null
    private val interrupts = mutableMapOf<String, DownloadState>()

    /** A one-off imperative read of the whole queue (for headless callers/tests; the UI uses [tasks]). */
    suspend fun snapshot(): List<DownloadTask> = store.all()

    /**
     * Load the persisted queue on startup. The queue does NOT auto-start: anything left running or queued
     * — in-flight when the app was killed/crashed, or still waiting — is reset to [DownloadState.PAUSED]
     * so the user explicitly resumes it. Terminal/failed/already-paused tasks are kept as-is.
     */
    suspend fun load() {
        store.all().forEach { task ->
            if (task.state.isActive || task.state == DownloadState.QUEUED) {
                store.upsert(task.copy(state = DownloadState.PAUSED, updatedAt = now()))
            }
        }
    }

    fun enqueue(request: DownloadRequest): String {
        val id = "${now()}-${request.videoId}-${Random.nextInt(1_000_000)}"
        val task = DownloadTask(
            id = id,
            videoId = request.videoId,
            title = request.title,
            artist = request.artist,
            thumbnailUrl = request.thumbnailUrl,
            format = request.format,
            requestedHeight = request.requestedHeight,
            state = DownloadState.QUEUED,
            downloadedBytes = 0,
            totalBytes = 0,
            fileUri = null,
            errorMessage = null,
            createdAt = now(),
            updatedAt = now(),
        )
        scope.launch {
            store.upsert(task)
            changes.emit(DownloadChange.Added(task))
            startNextIfIdle()
        }
        return id
    }

    /** Pause the whole queue: stop the active download and hold every queued task. */
    fun pause(@Suppress("UNUSED_PARAMETER") id: String) = scope.launch {
        val active = gate.withLock { activeId?.also { interrupts[it] = DownloadState.PAUSED }?.let { activeJob } }
        active?.cancel()
        store.all().filter { it.state == DownloadState.QUEUED }.forEach { setState(it.id, DownloadState.PAUSED) }
    }.let {}

    fun resume(id: String) = scope.launch {
        val task = store.get(id) ?: return@launch
        if (task.state == DownloadState.PAUSED || task.state == DownloadState.FAILED) {
            setState(id, DownloadState.QUEUED, error = null)
            startNextIfIdle()
        }
    }.let {}

    /** Alias for [resume] on a failed task — the same "back to QUEUED" transition. */
    fun retry(id: String) = resume(id)

    fun cancel(id: String) = scope.launch {
        val active = gate.withLock { if (activeId == id) { interrupts[id] = DownloadState.CANCELED; activeJob } else null }
        if (active != null) {
            active.cancel()
        } else {
            cleanupTemp(id)
            setState(id, DownloadState.CANCELED)
        }
    }.let {}

    /** Remove a task (and its temp files) entirely. The finished output file is left on disk. */
    fun remove(id: String) = scope.launch {
        val active = gate.withLock { if (activeId == id) { interrupts[id] = DownloadState.CANCELED; activeJob } else null }
        active?.cancel()
        cleanupTemp(id)
        store.delete(id)
        changes.emit(DownloadChange.Removed(id))
    }.let {}

    // --- worker --------------------------------------------------------------------------------------

    /** Start the oldest queued task if no task is currently active. */
    private fun startNextIfIdle() {
        scope.launch {
            gate.withLock {
                if (activeId != null) return@withLock
                val next = store.nextQueued() ?: return@withLock
                activeId = next.id
                val job = scope.launch { execute(next) }
                activeJob = job
                // On completion free the slot and advance — unless this task was explicitly paused (pausing
                // holds the whole queue), in which case the queue stays put until the user resumes.
                job.invokeOnCompletion {
                    scope.launch {
                        val paused = gate.withLock {
                            activeId = null
                            activeJob = null
                            interrupts.remove(next.id) == DownloadState.PAUSED
                        }
                        if (!paused) startNextIfIdle()
                    }
                }
            }
        }
    }

    private suspend fun execute(task: DownloadTask) {
        try {
            val result = pipeline.run(
                task,
                captions = captionPreference(),
                onPreparing = { setStateAsync(task.id, DownloadState.PREPARING) },
                onDownloading = { setStateAsync(task.id, DownloadState.DOWNLOADING) },
                onProcessing = { setStateAsync(task.id, DownloadState.PROCESSING) },
                onProgress = { done, total -> setProgressAsync(task.id, done, total) },
            )
            store.updateCompleted(task.id, result.uri, result.bytes, now())
            changes.emit(DownloadChange.Completed(task.id, result.uri, result.bytes))
            store.get(task.id)?.let { onCompleted?.invoke(it, result) }
        } catch (c: CancellationException) {
            withContext(NonCancellable) {
                val target = gate.withLock { interrupts[task.id] } ?: DownloadState.PAUSED
                if (target == DownloadState.CANCELED) cleanupTemp(task.id)
                setState(task.id, target)
            }
            throw c // let the job complete as cancelled; invokeOnCompletion decides whether to advance
        } catch (e: Throwable) {
            // Log the full technical failure here; the user sees only a code. An uncoded exception
            // (network, SABR, …) gets the E70001 fallback so every failure is reportable.
            logger?.log(Logger.Level.WARN, LOG_CTX, "download ${task.id} failed: ${e.message}", e)
            setState(task.id, DownloadState.FAILED, error = userFacingError(e))
        }
    }

    // --- writes (DB + change event) ------------------------------------------------------------------

    private suspend fun setState(id: String, state: DownloadState, error: String? = null) {
        store.updateState(id, state, error, now())
        changes.emit(DownloadChange.StateChanged(id, state, error))
    }

    private fun setStateAsync(id: String, state: DownloadState) = scope.launch { setState(id, state) }.let {}

    private fun setProgressAsync(id: String, done: Long, total: Long) = scope.launch {
        store.updateProgress(id, done, total, now())
        changes.emit(DownloadChange.ProgressChanged(id, done, total))
    }.let {}

    private fun userFacingError(e: Throwable): String {
        val coded = e.codedError()
        return when {
            coded?.userMessage != null -> "${coded.userMessage} (${coded.code})"
            else -> "Something went wrong (${coded?.code ?: "E70001"})"
        }
    }

    private fun cleanupTemp(id: String) {
        listOf("$id.audio", "$id.video", "$id.out").forEach { name ->
            runCatching { storage.scratch(name).delete() }
        }
    }

    private companion object {
        val LOG_CTX = listOf("download", "service")
    }
}
