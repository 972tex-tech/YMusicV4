package io.ymusic.remoteconfig

import io.ymusic.remoteconfig.RemoteConfigData.Companion.value
import kmpcommon.io.asAsyncSource
import kmpcommon.io.buffer
import kmpcommon.log.Logger
import kmpcommon.log.info
import kmphttp.HttpStatusException
import kmphttp.json.JsonReader
import kmphttp.json.JsonWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.buffer
import okio.use

/**
 * Five minutes. Long enough that hammering [RemoteConfig.checkUpdate] from every screen costs one
 * request, short enough that a scraping fix reaches a running app while someone is still using it.
 */
const val REMOTE_CONFIG_MIN_INTERVAL_MS: Long = 5 * 60 * 1000L

/**
 * The app's view of the remote config: feature flags, the newest JS bundle revisions, and whether a
 * newer build exists. How the service is reached is [ApiService]'s business; this class owns only
 * the policy — the throttle, the on-disk cache, and what counts as fresh.
 *
 * [config] holds the last answer the app managed to read — the body cached by the previous run until
 * the first fetch lands, then whatever the service said. A fetch that fails leaves the previous value
 * in place: a service outage must not change how the app behaves.
 *
 * Nothing polls. [checkUpdate] is called from the places a person actually arrives at — the menu,
 * discover, search, play — and throttles itself to one fetch per [REMOTE_CONFIG_MIN_INTERVAL_MS], so
 * an idle app in the background makes no requests and keeps no timer alive to wake the device for.
 *
 * What gets cached for the next cold start is the parsed [RemoteConfigData], re-encoded. A field
 * this build does not know is therefore not preserved across a restart; it arrives again on the
 * first refresh.
 */
class RemoteConfig(
    /** Null when this build has no config service: it runs on the cache, or on DEFAULTS. */
    private val api: ApiService?,
    /** Where the last answer is kept for the next cold start. Written and read as a stream. */
    private val cacheFile: Path,
    private val fileSystem: FileSystem,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val log: Logger? = null,
) {
    private var latest: RemoteConfigData? = null
    private val _config = MutableSharedFlow<RemoteConfigData>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val config: SharedFlow<RemoteConfigData> = _config.asSharedFlow()

    private val mutex = Mutex()
    private var initialized = false
    private var inFlight: Job? = null

    init {
        scope.launch {
            initIfNotYet()
            // Guarded: fetch() requires a service, and a build without one is a valid state (it
            // runs on the cache, or on DEFAULTS) rather than a crash on every start.
            if (api != null) fetch().forget()
        }
    }

    private fun publish(data: RemoteConfigData) {
        latest = data
        _config.tryEmit(data) // always success
    }

    private suspend fun initIfNotYet() {
        if (initialized) return
        mutex.withLock {
            if (initialized) return
            // The whole read is guarded, not just the parse: exists() and source() touch the file
            // system too, and this runs from init on a scope whose cancellation would take the app
            // with it. Starting on DEFAULTS is always better than not starting.
            val latestValue = try {
                if (fileSystem.exists(cacheFile)) {
                    RemoteConfigData.from(
                        fileSystem.source(cacheFile).use { source ->
                            JsonReader(source.asAsyncSource().buffer()).readObject()
                        },
                        now,
                    )
                } else {
                    RemoteConfigData.DEFAULTS
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                // Best effort: a cache that cannot be read is worse than none, but failing to
                // remove it must not become a second failure on top of the first.
                runCatching { fileSystem.delete(cacheFile) }.exceptionOrNull()?.let(error::addSuppressed)
                log?.captureError(error, "read config cache failure")
                RemoteConfigData.DEFAULTS
            }
            initialized = true
            publish(latestValue)
            return
        }
    }

    /**
     * Bring the config up to date in the background if it is stale, otherwise do nothing.
     *
     * Returns immediately and never fails: call it freely from anywhere a person lands and let the
     * throttle decide. Only a SUCCESSFUL fetch moves [RemoteConfigData.updatedAt], so an outage does
     * not buy the service a quiet window.
     */
    fun checkUpdate(minIntervalMs: Long = REMOTE_CONFIG_MIN_INTERVAL_MS) {
        // Fast path, off the caller's thread budget: no service, one already running, or still fresh.
        if (api == null || inFlight?.isActive == true) return
        if (isFresh(latest, minIntervalMs)) return

        scope.launch {
            initIfNotYet()
            // Re-checked inside: initIfNotYet may have just loaded a cache newer than the window.
            if (isFresh(latest, minIntervalMs)) return@launch
            fetch().forget()
        }
    }

    /** Null is never fresh — nothing has been loaded yet, so anything is an improvement. */
    private fun isFresh(data: RemoteConfigData?, maxAgeMs: Long): Boolean =
        data != null && now() - data.updatedAt < maxAgeMs

    /**
     * The config, fetching and waiting when what we hold is older than
     * [REMOTE_CONFIG_MIN_INTERVAL_MS].
     *
     * No max-age parameter: the freshness policy is this class's, not the caller's. A screen asking
     * for config has no basis for deciding how stale is acceptable, and letting each one pick would
     * make the request rate a function of which screens happen to be open.
     *
     * Never DEFAULTS-by-accident: a caller that waits gets the cache when there is one, and only the
     * defaults when neither cache nor fetch produced anything.
     */
    suspend fun getConfig(): RemoteConfigData {
        initIfNotYet()
        if (api == null || isFresh(latest, REMOTE_CONFIG_MIN_INTERVAL_MS)) return checkNotNull(latest)
        fetch().join()
        return checkNotNull(latest)
    }

    private suspend fun fetch(): Job {
        // fetch update is app scope, not callable by caller
        check(initialized) { "Do init first" }
        val api = checkNotNull(api) { "api = null" }
        inFlight?.takeIf { it.isActive }?.let { return it }
        mutex.withLock {
            inFlight?.takeIf { it.isActive }?.let { return it }

            return scope.launch {
                try {
                    val data = api.getConfig()
                    try {
                        cache(data)
                    } catch (error: Throwable) {
                        log?.captureError(error, "Write config cache failure")
                    }
                    publish(data)
                } catch (cancel: CancellationException) {
                    // Rethrown, not logged: cancelling the scope is not a fetch failure, and
                    // swallowing it here would leave the coroutine reported as completed normally.
                    throw cancel
                } catch (refused: HttpStatusException) {
                    // A refusal the server may recover from is a state; one it will not is a bug.
                    // The last good value stays published either way.
                    if (refused.retryable) {
                        log?.info("Fetch config: ${refused.message}")
                    } else {
                        log?.captureError(refused, "Fetch config failed")
                    }
                } catch (offline: IOException) {
                    // Being offline is a state, not a bug: retrying is the fix, and checkUpdate
                    // will — nothing to capture.
                    log?.info("Fetch config: ${offline.message}")
                } catch (error: Throwable) {
                    log?.captureError(error, "Fetch config failed")
                }
                // Not nulled on completion: the body can finish before `also` below assigns the
                // field, which would park a COMPLETED Job here forever. Every reader tests
                // isActive instead.
            }.also {
                inFlight = it
            }
        }
    }

    private fun cache(data: RemoteConfigData) {
        val tmp = cacheFile.parent?.resolve(cacheFile.name + ".tmp") ?: return
        cacheFile.parent?.let { fileSystem.createDirectories(it) }
        val writer = JsonWriter.Sync()
        writer.value(data)
        // The document is a few hundred bytes, so it is written whole and drained once. okio's
        // writeAll moves segments rather than copying them.
        fileSystem.sink(tmp).use { it.buffer().use { out -> out.writeAll(writer.buffer) } }
        fileSystem.atomicMove(tmp, cacheFile)
    }

    @Suppress("UnusedReceiverParameter")
    private fun Job.forget() = Unit
}
