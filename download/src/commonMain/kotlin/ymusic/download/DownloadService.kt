package ymusic.download

import kmpcommon.log.Logger
import kmphttp.KmpHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import ymusic.ytmedia.cache.MediaCache
import kotlin.random.Random

/**
 * Manages the download queue: enqueue / pause / resume / cancel / retry / remove, with the live list
 * exposed as [tasks] for the UI and mirrored to [store] so it survives a restart. One task runs at a
 * time — obtaining each video's SABR inputs drives the shared WebView (via the [SabrSpecResolver]), so
 * concurrent priming would contend; the byte download + ffmpeg then run to completion before the next.
 *
 * Pause and cancel both cancel the active [Job]; the difference is the recorded terminal state and
 * whether the partially-downloaded files are kept (pause, for resume via [OkioDownloadFile]'s length
 * resume point) or deleted (cancel). Control methods are fire-and-forget — they launch into [scope].
 */
class DownloadService(
    private val store: DownloadStore,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    resolver: SabrSpecResolver,
    client: KmpHttpClient,
    userAgent: String,
    ffmpeg: Ffmpeg?,
    private val fileSystem: FileSystem,
    private val workDir: Path,
    outputDir: Path,
    mediaCache: MediaCache? = null,
    private val logger: Logger? = null,
    /** Optional hook to register a finished file into the local library (media-store); shell-provided. */
    private val onCompleted: (suspend (DownloadTask, DownloadPipeline.Result) -> Unit)? = null,
) {
    /** True where ffmpeg exists (Android/desktop). False on iOS — the UI hides MP3/video outputs there. */
    val ffmpegAvailable: Boolean = ffmpeg != null

    private val pipeline = DownloadPipeline(
        resolver, client, userAgent, ffmpeg, fileSystem, workDir, outputDir, mediaCache, logger,
    )

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val lock = Mutex()
    private val pumpSignal = Channel<Unit>(Channel.CONFLATED)
    private var activeJob: Job? = null
    private var activeId: String? = null

    /** Set just before cancelling the active job so the interrupt handler knows PAUSED vs CANCELED. */
    private val interrupts = mutableMapOf<String, DownloadState>()

    init {
        scope.launch { pumpLoop() }
    }

    /** Load the persisted queue on startup; any task left mid-flight (app killed) is re-queued. */
    suspend fun load() {
        val restored = store.all().map { task ->
            if (task.state.isActive || task.state == DownloadState.PREPARING) {
                task.copy(state = DownloadState.QUEUED, updatedAt = now())
            } else {
                task
            }
        }
        restored.filter { it.state == DownloadState.QUEUED }.forEach { store.upsert(it) }
        _tasks.value = restored
        pumpSignal.trySend(Unit)
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
            put(task)
            pumpSignal.trySend(Unit)
        }
        return id
    }

    fun pause(id: String) = scope.launch {
        val task = current(id) ?: return@launch
        if (task.state == DownloadState.QUEUED) {
            put(task.copy(state = DownloadState.PAUSED, updatedAt = now()))
        } else if (task.id == activeId) {
            lock.withLock { interrupts[id] = DownloadState.PAUSED }
            activeJob?.cancel()
        }
    }.let {}

    fun resume(id: String) = scope.launch {
        val task = current(id) ?: return@launch
        if (task.state == DownloadState.PAUSED || task.state == DownloadState.FAILED) {
            put(task.copy(state = DownloadState.QUEUED, errorMessage = null, updatedAt = now()))
            pumpSignal.trySend(Unit)
        }
    }.let {}

    /** Alias for [resume] on a failed task — the same "back to QUEUED" transition. */
    fun retry(id: String) = resume(id)

    fun cancel(id: String) = scope.launch {
        val task = current(id) ?: return@launch
        if (task.id == activeId) {
            lock.withLock { interrupts[id] = DownloadState.CANCELED }
            activeJob?.cancel()
        } else {
            cleanupTemp(id)
            put(task.copy(state = DownloadState.CANCELED, updatedAt = now()))
        }
    }.let {}

    /** Remove a task from the queue entirely (and its temp files). The finished output file is left on
     *  disk — it's a normal library file by then. */
    fun remove(id: String) = scope.launch {
        if (id == activeId) {
            lock.withLock { interrupts[id] = DownloadState.CANCELED }
            activeJob?.cancel()
        }
        cleanupTemp(id)
        store.delete(id)
        _tasks.update { list -> list.filterNot { it.id == id } }
    }.let {}

    // --- worker pump ---------------------------------------------------------------------------------

    private suspend fun pumpLoop() {
        for (signal in pumpSignal) {
            while (true) {
                val next = lock.withLock {
                    if (activeId != null) return@withLock null
                    _tasks.value.firstOrNull { it.state == DownloadState.QUEUED }?.also { activeId = it.id }
                } ?: break
                runTask(next)
            }
        }
    }

    private suspend fun runTask(task: DownloadTask) {
        val job = scope.launch { execute(task) }
        activeJob = job
        job.join()
        lock.withLock {
            activeJob = null
            activeId = null
            interrupts.remove(task.id)
        }
    }

    private suspend fun execute(task: DownloadTask) {
        try {
            val result = pipeline.run(
                task,
                onPreparing = { setStateAsync(task.id, DownloadState.PREPARING) },
                onDownloading = { setStateAsync(task.id, DownloadState.DOWNLOADING) },
                onProcessing = { setStateAsync(task.id, DownloadState.PROCESSING) },
                onProgress = { done, total -> setProgressAsync(task.id, done, total) },
            )
            val bytes = runCatching { fileSystem.metadata(result.file).size ?: 0L }.getOrDefault(0L)
            put(current(task.id)!!.copy(
                state = DownloadState.COMPLETED,
                // The on-disk path for now; on Android this must become the content:// SAF document written
                // into the user-picked Library folder (see the TODO board) — the onCompleted hook is where
                // the shell would publish the file there and rewrite this to that content:// uri.
                fileUri = result.file.toString(),
                downloadedBytes = bytes,
                totalBytes = bytes,
                errorMessage = null,
                updatedAt = now(),
            ))
            onCompleted?.invoke(current(task.id)!!, result)
        } catch (c: CancellationException) {
            withContext(NonCancellable) {
                val target = lock.withLock { interrupts[task.id] } ?: DownloadState.PAUSED
                if (target == DownloadState.CANCELED) cleanupTemp(task.id)
                put(current(task.id)!!.copy(state = target, updatedAt = now()))
            }
            throw c
        } catch (e: Throwable) {
            logger?.log(Logger.Level.WARN, LOG_CTX, "download ${task.id} failed: ${e.message}", e)
            put(current(task.id)!!.copy(state = DownloadState.FAILED, errorMessage = e.message ?: "failed", updatedAt = now()))
        }
    }

    // --- state helpers -------------------------------------------------------------------------------

    private fun current(id: String) = _tasks.value.firstOrNull { it.id == id }

    private fun setStateAsync(id: String, state: DownloadState) = scope.launch {
        current(id)?.let { put(it.copy(state = state, updatedAt = now())) }
    }.let {}

    private fun setProgressAsync(id: String, done: Long, total: Long) = scope.launch {
        current(id)?.let { put(it.copy(downloadedBytes = done, totalBytes = total, updatedAt = now())) }
    }.let {}

    /** Update the in-memory list and durably persist one task (insert or replace). */
    private suspend fun put(task: DownloadTask) {
        _tasks.update { list ->
            if (list.any { it.id == task.id }) list.map { if (it.id == task.id) task else it } else list + task
        }
        store.upsert(task)
    }

    private fun cleanupTemp(id: String) {
        runCatching { fileSystem.delete(workDir / "$id.audio", mustExist = false) }
        runCatching { fileSystem.delete(workDir / "$id.video", mustExist = false) }
    }

    private companion object {
        val LOG_CTX = listOf("download", "service")
    }
}
