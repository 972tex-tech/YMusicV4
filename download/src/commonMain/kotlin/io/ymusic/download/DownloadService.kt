package io.ymusic.download

import io.ymusic.ytmedia.cache.MediaCache
import io.ymusic.ytmedia.cache.SegmentIndex
import kmpcommon.error.TechnicalException
import kmpcommon.error.codedError
import kmpcommon.log.Logger
import kmphttp.KmpHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Manages the download queue. The DB ([store]) is the single source of truth — the service holds no task
 * list, only the [maxConcurrent] worker slots; it just dispatches [changes] events that a UI-owned
 * [DownloadTasksAggregator] folds into a list while the screen is shown.
 *
 * Queue model: up to [maxConcurrent] tasks run at once (priming the WebView still serializes). Adding a
 * task fills a free slot; when a task finishes or is canceled, the next queued one takes the slot.
 * **[pause] is per-task** — it frees that slot and the queue keeps flowing; **[pauseAll] holds the whole
 * queue**. [resume]/[resumeAll] bring tasks back. Nothing auto-starts on [load] (a crash leaves tasks
 * PAUSED).
 *
 * Pause and cancel both cancel the task's [Job]; the difference is the recorded state and whether the
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
    /** How many downloads run at once. Priming (the WebView resolve) still serializes; this bounds the
     *  concurrent byte-download + ffmpeg work. */
    private val maxConcurrent: Int = 3,
    mediaCache: MediaCache? = null,
    /** Persistent segment index used to resume SABR downloads (share the player cache's index). */
    segmentIndex: SegmentIndex? = null,
    logger: Logger? = null,
    /** Current caption selection, read fresh per download (the shell sources it from settings). Keeps the
     *  service headless and avoids persisting the preference on each task. Defaults to no captions. */
    private val captionPreference: suspend () -> CaptionPreference = { CaptionPreference.None },
    /** The user's chosen download-location folder uri, read fresh at publish time (shell sources it from
     *  settings, falling back to the platform's default downloads dir). Always concrete — the UX requires
     *  a location before a download runs. */
    private val downloadFolder: suspend () -> String,
    /** Optional hook to register a finished file into the local library (media-store); shell-provided. */
    private val onCompleted: (suspend (DownloadTask, DownloadPipeline.Result) -> Unit)? = null,
    /** The player-response JSON for a video (no PO token) — what [probe] reads the options from. */
    private val playerResponse: (suspend (videoId: String) -> String)? = null,
) {
    /** True where ffmpeg exists (Android/desktop). False on iOS — the UI hides MP3/video outputs there. */
    val ffmpegAvailable: Boolean = ffmpeg != null

    private val log = logger?.fork("download")?.fork("service")

    private val pipeline = DownloadPipeline(
        resolver, client, userAgent, ffmpeg, storage, mediaCache, segmentIndex, logger,
    )

    private val _changes = MutableSharedFlow<DownloadChange>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Per-task change events. A UI builds a [DownloadTasksAggregator] over this + [snapshot] to render the
     *  queue while it's on screen; nothing is materialized here. */
    val changes: SharedFlow<DownloadChange> = _changes.asSharedFlow()

    // The worker slots. `gate` guards them, the global-pause flag, and the interrupt reasons.
    private val gate = Mutex()
    private var queuePaused = false
    private val active = mutableMapOf<String, Job>()
    private val interrupts = mutableMapOf<String, DownloadState>()

    private val initMutex = Mutex()
    private var initialized = false

    /**
     * One-time startup recovery, run lazily on first use (no explicit start-up call): anything left
     * running or queued — in-flight when the app was killed/crashed, or still waiting — is reset to
     * [DownloadState.PAUSED] so the queue never auto-starts and the user resumes explicitly.
     * Terminal/failed/already-paused tasks are kept. Idempotent.
     */
    internal suspend fun initIfNotYet() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            store.pauseActiveAndQueued(now())
            initialized = true
        }
    }

    /** A one-off imperative read of the whole queue (headless callers/tests, and the aggregator's seed). */
    suspend fun snapshot(): List<DownloadTask> {
        initIfNotYet()
        return store.all()
    }

    /** What [videoId] can be downloaded as — the options dialog's input. */
    suspend fun probe(videoId: String): DownloadOptions {
        val fetch = playerResponse ?: throw TechnicalException("E70040", "download options: no player-response source")
        val json = fetch(videoId)
        return withContext(Dispatchers.Default) { DownloadOptions.parse(json, ffmpegAvailable) }
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
            fileName = request.fileName,
            audioTrackId = request.audioTrackId,
            captions = request.captions,
            audioLanguage = request.audioLanguage,
            captionLanguage = request.captionLanguage,
            state = DownloadState.QUEUED,
            downloadedBytes = 0,
            totalBytes = 0,
            fileUri = null,
            errorMessage = null,
            createdAt = now(),
            updatedAt = now(),
        )
        scope.launch {
            initIfNotYet()
            store.upsert(task)
            _changes.emit(DownloadChange.Added(task))
            fillSlots()
        }
        return id
    }

    /** Pause one task and free its slot; the next queued task takes it (the queue keeps flowing). */
    fun pause(id: String) {
        // Act under the same lock fillSlots claims with, so a QUEUED task can't be started between the
        // read and the pause (which would lose the pause, or leave a "paused" task still downloading).
        scope.launch {
            gate.withLock {
                val running = active[id]
                if (running != null) {
                    interrupts[id] = DownloadState.PAUSED
                    running.cancel() // execute → PAUSED; completion frees the slot and fillSlots continues
                } else if (store.get(id)?.state == DownloadState.QUEUED) {
                    setState(id, DownloadState.PAUSED)
                }
            }
        }
    }

    fun resume(id: String) {
        scope.launch {
            val task = store.get(id) ?: return@launch
            if (task.state == DownloadState.PAUSED || task.state == DownloadState.FAILED) {
                gate.withLock { queuePaused = false } // resuming a task un-holds the queue
                setState(id, DownloadState.QUEUED, error = null)
                fillSlots()
            }
        }
    }

    /** Alias for [resume] on a failed task — the same "back to QUEUED" transition. */
    fun retry(id: String) = resume(id)

    fun cancel(id: String) {
        scope.launch {
            gate.withLock {
                val running = active[id]
                if (running != null) {
                    interrupts[id] = DownloadState.CANCELED
                    running.cancel()
                } else {
                    cleanupTemp(id)
                    setState(id, DownloadState.CANCELED)
                }
            }
        }
    }

    /** Remove a task (and its temp files) entirely. The finished output file is left on disk. */
    fun remove(id: String) {
        // Decide + delete under the claim lock so a QUEUED task can't be started into a deleted row. An
        // active task is cancelled — its execute cleans up its own scratch on the CANCELED path; a
        // non-active one is cleaned up here.
        scope.launch {
            gate.withLock {
                val running = active[id]
                if (running != null) {
                    interrupts[id] = DownloadState.CANCELED
                    running.cancel()
                } else {
                    cleanupTemp(id)
                }
                store.delete(id)
            }
            _changes.emit(DownloadChange.Removed(id))
        }
    }

    /** Global pause: hold the whole queue — stop every active download and every queued task. */
    fun pauseAll() {
        scope.launch {
            gate.withLock {
                queuePaused = true
                active.forEach { (id, job) -> interrupts[id] = DownloadState.PAUSED; job.cancel() }
            }
            store.all().filter { it.state == DownloadState.QUEUED }.forEach { setState(it.id, DownloadState.PAUSED) }
        }
    }

    /** Global resume: re-queue every paused task and run up to [maxConcurrent] of them. */
    fun resumeAll() {
        scope.launch {
            gate.withLock { queuePaused = false }
            store.all().filter { it.state == DownloadState.PAUSED }.forEach { setState(it.id, DownloadState.QUEUED) }
            fillSlots()
        }
    }

    // --- worker --------------------------------------------------------------------------------------

    /** Start queued tasks until [maxConcurrent] are running (unless the queue is globally paused). Each
     *  pick is claimed as PREPARING under the lock so the next [DownloadStore.nextQueued] skips it. */
    private fun fillSlots() {
        scope.launch {
            gate.withLock {
                if (queuePaused) return@withLock
                while (active.size < maxConcurrent) {
                    val next = store.nextQueued() ?: break
                    setState(next.id, DownloadState.PREPARING)
                    val job = scope.launch { execute(next) }
                    active[next.id] = job
                    job.invokeOnCompletion {
                        scope.launch {
                            gate.withLock { active.remove(next.id); interrupts.remove(next.id) }
                            fillSlots()
                        }
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
                destinationFolder = downloadFolder(),
                onPreparing = { setStateAsync(task.id, DownloadState.PREPARING) },
                onDownloading = { setStateAsync(task.id, DownloadState.DOWNLOADING) },
                onProcessing = { setStateAsync(task.id, DownloadState.PROCESSING) },
                onProgress = { done, total -> setProgressAsync(task.id, done, total) },
            )
            store.updateCompleted(task.id, result.uri, result.bytes, now())
            _changes.emit(DownloadChange.Completed(task.id, result.uri, result.bytes))
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
            log?.fork("task", mapOf("id" to task.id, "videoId" to task.videoId))
                ?.captureError(e, "download failed")
            setState(task.id, DownloadState.FAILED, error = userFacingError(e))
        }
    }

    // --- writes (DB + change event) ------------------------------------------------------------------

    private suspend fun setState(id: String, state: DownloadState, error: String? = null) {
        store.updateState(id, state, error, now())
        _changes.emit(DownloadChange.StateChanged(id, state, error))
    }

    private fun setStateAsync(id: String, state: DownloadState) {
        scope.launch { setState(id, state) }
    }

    private fun setProgressAsync(id: String, done: Long, total: Long) {
        scope.launch {
            store.updateProgress(id, done, total, now())
            _changes.emit(DownloadChange.ProgressChanged(id, done, total))
        }
    }

    private fun userFacingError(e: Throwable): String {
        val coded = e.codedError()
        return when {
            coded?.userMessage != null -> "${coded.userMessage} (${coded.code})"
            else -> "Something went wrong (${coded?.code ?: "E70001"})"
        }
    }

    private fun cleanupTemp(id: String) {
        runCatching { storage.deleteScratch(id) }
            .onFailure { log?.log(Logger.Level.WARN, "scratch cleanup failed for $id (files leak until next run)", it) }
    }
}
