package io.ymusic.dynamicengine

import io.ymusic.dynamicengine.internal.ENGINE_JS
import io.ymusic.quickjs.QuickJsRuntime
import io.ymusic.settings.SettingsStore
import kmpcommon.log.Logger
import kmphttp.CookieJar
import kmphttp.KmpHttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.Buffer
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Owns **the current [DynamicEngine]** and the remote-update seam: [swap] boots a new bundle,
 * health-checks it, flips new work onto it, and retires the old engine gracefully — the reload
 * path that ships YouTube feed-shape fixes without an app release.
 *
 * Retirement is Node's SIGINT over pipes: the old engine's `"shutdown"` command tells the bundle
 * to stop repeat work, then the manager waits (bounded by [drainTimeoutMs]) for the engine to
 * drain to idle — in-flight jobs finish, cron pipes close — and closes it. A wedged engine hits
 * the deadline and is force-closed; its callers get the typed close error and retry on the new
 * engine (scrape jobs are idempotent reads).
 *
 * Task methods route through [use], which pins the engine current *at call start* — a swap mid-job
 * never reroutes or aborts that job. Swaps are serialized; the flip itself is a lock-protected
 * reference write, so [use] never waits on a swap in progress.
 */
@OptIn(ExperimentalAtomicApi::class)
class DynamicEngineManager(
    private val client: KmpHttpClient,
    private val appVersion: Int,
    private val logger: Logger? = null,
    private val baselineJs: String = ENGINE_JS,
    private val cacheStore: CacheStore? = null,
    private val drainTimeoutMs: Long = 30_000L,
    /** The app-owned cookie store the cookie protocol serves (see [DynamicEngine.create]). */
    private val cookieJar: CookieJar = client.cookieJar,
    private val settings: SettingsStore? = null,
    /** Fail-fast on engine-thread stalls (debug builds only): a task that held the thread 50ms+
     *  in one stretch logs ERROR and THROWS on the engine thread (StrictMode's penaltyDeath — a
     *  definite bug, unmissable in development); a task that waited 50ms+ for the thread only
     *  logs WARN (congestion, possibly just load). When false (release), no watchdog is armed at
     *  all: zero overhead, zero output. */
    private val strictStints: Boolean = false,
) {

    private val stintLog = logger?.fork("engine-stint")
    private val mgrLog = logger?.fork("engine-manager")

    private class Slot(val engine: DynamicEngine) {
        val inFlight = AtomicInt(0)
        val retired = AtomicBoolean(false)
        val drained = CompletableDeferred<Unit>()

        fun enter() {
            inFlight.addAndFetch(1)
        }

        fun exit() {
            if (inFlight.addAndFetch(-1) == 0 && retired.load()) drained.complete(Unit)
        }

        fun retire() {
            retired.store(true)
            if (inFlight.load() == 0) drained.complete(Unit)
        }
    }

    private val activeLock = Mutex() // guards the `active` reference only — never held while working
    private var active: Slot? = null

    private val swapLock = Mutex() // serializes start/swap/close end to end

    // The ONE JS thread every engine this manager boots is confined to, across swaps — created
    // engines run on it for life (DynamicEngine.create's synchronous contract), so it outlives
    // each engine and closes with the manager. [strictStints] (debug builds) arms the watchdog:
    // a task that HELD the thread 50ms+ in one stretch (the culprit — a definite bug) logs ERROR
    // and throws on the engine thread, so the stall is unmissable in development; a task that
    // WAITED 50ms+ for the thread (the victim — cumulative congestion, possibly just load) only
    // logs WARN. Release arms nothing: zero overhead, zero output.
    private val engineDispatcher = QuickJsRuntime.newDispatcher(
        slowStintMs = if (strictStints) SLOW_STINT_MS else 0L,
        onSlowStint = if (!strictStints) {
            null
        } else {
            { ms ->
                val message = "engine thread held for ${ms}ms by one task (budget ${SLOW_STINT_MS}ms) — " +
                    "every pipe, timer and command stalls while the thread is held"
                runCatching { stintLog?.log(Logger.Level.ERROR, message) }
                throw IllegalStateException(message)
            }
        },
        slowWaitMs = if (strictStints) SLOW_STINT_MS else 0L,
        onSlowWait = if (!strictStints) {
            null
        } else {
            { ms ->
                runCatching {
                    stintLog?.log(
                        Logger.Level.WARN,
                        "a task waited ${ms}ms for the engine thread (budget ${SLOW_STINT_MS}ms) — " +
                            "the thread was congested, though no single task held it over budget",
                    )
                }
            }
        },
    )

    // Boot + gate: the manager is the accept/reject seam for bundles, so IT drives the health
    // check — create itself just boots. A failed check closes the half-born engine here.
    private suspend fun createEngine(js: String): DynamicEngine = withContext(engineDispatcher) {
        val engine = DynamicEngine.create(client, appVersion, engineDispatcher, logger, js, cacheStore, cookieJar, settings)
        try {
            engine.healthCheck()
        } catch (t: Throwable) {
            runCatching { engine.closeNow() }
            throw t
        }
        engine
    }

    /** Whether the last retirement drained inside the deadline (vs. force-closed). Test seam. */
    internal var lastRetirementDrained: Boolean = true

    /** The current bundle's self-declared revision, or null before [start]. */
    suspend fun backendVersion(): Int? = activeLock.withLock { active?.engine?.backendVersion }

    /**
     * Boot the first engine. [remoteJs] is the persisted remotely-updated bundle, if any: when it
     * fails to load or health-check, the failure is LOGGED and the baked-in baseline boots instead
     * (a bad push must never brick the engine). A baseline failure is a build defect — it throws.
     * Returns the running bundle's backendVersion.
     */
    suspend fun start(remoteJs: String? = null): Int = swapLock.withLock {
        check(activeLock.withLock { active } == null) { "already started" }
        val engine = if (remoteJs == null) {
            createEngine(baselineJs)
        } else {
            try {
                createEngine(remoteJs)
            } catch (e: EngineError) {
                mgrLog?.log(
                    Logger.Level.WARN,
                    "${e.code} remote bundle rejected at boot, falling back to baseline: ${e.message}",
                    e,
                )
                createEngine(baselineJs)
            }
        }
        activeLock.withLock { active = Slot(engine) }
        checkNotNull(engine.backendVersion) // createEngine health-checked
    }

    /**
     * Reload: boot [newJs], health-check it, flip new work onto it, then retire the old engine
     * (shutdown signal → bounded drain → close). Throws the typed [EngineError] (`E20020`/`E20021`
     * or the bundle's own code) when the new bundle is rejected — the current engine keeps
     * serving, unchanged. The flip happens BEFORE the drain: new jobs never wait on (or run on)
     * the outgoing engine. Returns the new bundle's backendVersion.
     */
    suspend fun swap(newJs: String): Int = swapLock.withLock {
        val fresh = createEngine(newJs) // throws typed on rejection
        val old = activeLock.withLock {
            val prev = active
            active = Slot(fresh)
            prev
        }
        mgrLog?.log(
            Logger.Level.INFO,
            "engine ${old?.engine?.backendVersion} -> ${fresh.backendVersion}",
        )
        if (old != null) retire(old)
        checkNotNull(fresh.backendVersion) // createEngine health-checked
    }

    /** Run a task on the engine that is current NOW — pinned for the whole block, so a concurrent
     *  [swap] neither reroutes nor aborts it (the old engine drains until this exits). */
    internal suspend fun <T> use(block: suspend (DynamicEngine) -> T): T {
        // enter() under the lock: entered-or-not-visible. Pinned outside it, a just-retired slot
        // could count us after its drain already completed and close under the running block.
        val slot = activeLock.withLock { (active ?: error("manager not started")).also { it.enter() } }
        try {
            return block(slot.engine)
        } finally {
            slot.exit()
        }
    }

    /** Close the current engine and its JS thread (no drain — app teardown; terminal). */
    suspend fun close(): Unit = swapLock.withLock {
        val slot = activeLock.withLock { val s = active; active = null; s }
        slot?.engine?.close()
        engineDispatcher.close()
    }

    private suspend fun retire(slot: Slot) {
        slot.retire()
        // Wall-clock deadline (Dispatchers.Default): virtual-time test dispatchers must not skip it.
        val drained = withContext(Dispatchers.Default) {
            withTimeoutOrNull(drainTimeoutMs) {
                signalShutdown(slot.engine)
                slot.drained.await() // Kotlin-initiated jobs out of [use]
                slot.engine.awaitIdle() // every pipe closed: jobs done, crons cleared, ack observed
            }
        } != null
        lastRetirementDrained = drained
        if (!drained) {
            mgrLog?.log(
                Logger.Level.WARN,
                "retired engine ${slot.engine.backendVersion} did not drain in ${drainTimeoutMs}ms — force-closing",
            )
        }
        slot.engine.close()
    }

    /** SIGINT, best-effort: a bundle without the lifecycle module refuses the open — skip (the
     *  drain deadline and close() are the real backstop, so a failure here is deliberately only
     *  logged-by-outcome, never propagated). */
    private suspend fun signalShutdown(engine: DynamicEngine) {
        runCatching {
            val pipe = engine.openPipe("shutdown")
            try {
                pipe.sink.close()
                val buf = Buffer()
                while (pipe.source.receive(buf, 4096L) != -1L) { /* EOF = the quiesced ack */ }
            } finally {
                pipe.source.close()
            }
        }
    }

    private companion object {
        /** One stint at/over this holds the engine thread long enough to feel: log it. */
        const val SLOW_STINT_MS = 50L
    }
}
