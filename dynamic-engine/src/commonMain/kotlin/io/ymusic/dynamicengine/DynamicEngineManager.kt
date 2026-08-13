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

    /** A booted engine + the JS thread it lives on ([dispatcher] closes after the engine does). */
    private class Slot(val engine: DynamicEngine, val dispatcher: kotlinx.coroutines.CloseableCoroutineDispatcher) {
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

    // Each engine gets its OWN JS thread for life (DynamicEngine.create's synchronous contract),
    // created with the engine and closed after it retires — so booting a new engine (bundle eval +
    // health check, easily 100ms+) never holds the thread the SERVING engine runs on: during a
    // swap the old engine keeps answering on its thread while the new one boots on a fresh one.
    // Shared host state tolerates the brief two-thread overlap: DiskCacheStore serializes on its
    // own Mutex + IO dispatcher, the cookie jar is the platform's thread-safe store, and
    // ParkedLoggers is deliberately unlocked (misses degrade to root).
    // [strictStints] (debug builds) arms the watchdog on every engine thread: a task that HELD
    // the thread 50ms+ in one stretch (the culprit — a definite bug) logs ERROR and throws on the
    // engine thread, so the stall is unmissable in development; a task that WAITED 50ms+ for the
    // thread (the victim — cumulative congestion, possibly just load) only logs WARN. Release
    // arms nothing: zero overhead, zero output.
    private fun newEngineDispatcher() = QuickJsRuntime.newDispatcher(
        slowStintMs = if (strictStints) SLOW_STINT_MS else 0L,
        onSlowStint = if (!strictStints) {
            null
        } else {
            { ms ->
                // Engine boot (the bundle eval + health check) is one irreducible >50ms stint by
                // nature — crashing on it teaches nothing, so boot answers to the relaxed
                // BOOT_STINT_MS instead; past even that, a boot stall is a real bug again.
                if (!booting || ms >= BOOT_STINT_MS) {
                    val budget = if (booting) BOOT_STINT_MS else SLOW_STINT_MS
                    val message = "engine thread held for ${ms}ms by one task (budget ${budget}ms) — " +
                        "every pipe, timer and command stalls while the thread is held"
                    stintLog?.log(Logger.Level.ERROR, message)
                    throw IllegalStateException(message)
                }
            }
        },
        slowWaitMs = if (strictStints) SLOW_STINT_MS else 0L,
        onSlowWait = if (!strictStints) {
            null
        } else {
            { ms ->
                stintLog?.log(
                    Logger.Level.WARN,
                    "a task waited ${ms}ms for the engine thread (budget ${SLOW_STINT_MS}ms) — " +
                        "the thread was congested, though no single task held it over budget",
                )
            }
        },
    )

    /** True while [createEngine] runs — the stint watchdog answers to [BOOT_STINT_MS] then. Boots
     *  are serialized under [swapLock]; the flag is manager-wide, so during a swap's ~1s boot the
     *  OLD engine's watchdog shares the grace — acceptable slack for one window per swap.
     *  Volatile: set on the caller's thread, read by the watchdog on the engine threads. */
    @kotlin.concurrent.Volatile
    private var booting = false

    // Boot + gate: the manager is the accept/reject seam for bundles, so IT drives the health
    // check — create itself just boots. The engine boots on its OWN fresh dispatcher (a serving
    // engine is never stalled by a boot); a failed boot closes the half-born engine AND its
    // never-activated thread here.
    private suspend fun createEngine(js: String): Slot {
        val dispatcher = newEngineDispatcher()
        booting = true
        try {
            return withContext(dispatcher) {
                val engine = DynamicEngine.create(client, appVersion, dispatcher, logger, js, cacheStore, cookieJar, settings)
                try {
                    engine.healthCheck()
                } catch (t: Throwable) {
                    runCatching { engine.closeNow() } // best-effort teardown; the boot failure below is the story
                    throw t
                }
                Slot(engine, dispatcher)
            }
        } catch (t: Throwable) {
            dispatcher.close()
            throw t
        } finally {
            booting = false
        }
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
        val slot = if (remoteJs == null) {
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
        activeLock.withLock { active = slot }
        checkNotNull(slot.engine.backendVersion) // createEngine health-checked
    }

    /**
     * Reload: boot [newJs], health-check it, flip new work onto it, then retire the old engine
     * (shutdown signal → bounded drain → close). Throws the typed [EngineError] (`E20020`/`E20021`
     * or the bundle's own code) when the new bundle is rejected — the current engine keeps
     * serving, unchanged. The flip happens BEFORE the drain: new jobs never wait on (or run on)
     * the outgoing engine. Returns the new bundle's backendVersion.
     */
    suspend fun swap(newJs: String): Int = swapLock.withLock {
        val fresh = createEngine(newJs) // throws typed on rejection; boots on its own thread
        val old = activeLock.withLock {
            val prev = active
            active = fresh
            prev
        }
        mgrLog?.log(
            Logger.Level.INFO,
            "engine ${old?.engine?.backendVersion} -> ${fresh.engine.backendVersion}",
        )
        if (old != null) retire(old)
        checkNotNull(fresh.engine.backendVersion) // createEngine health-checked
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
        slot?.dispatcher?.close()
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
        slot.dispatcher.close() // the retired engine's thread dies with it
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

        /** The stint budget while an engine BOOTS ([booting]): the bundle eval + health check is
         *  one irreducible stint that runs ~50-100ms on real phones (YMUSIC-KMP-5 crashed a debug
         *  launch at 56ms) — only a stall past this still throws during boot. */
        const val BOOT_STINT_MS = 500L
    }
}
