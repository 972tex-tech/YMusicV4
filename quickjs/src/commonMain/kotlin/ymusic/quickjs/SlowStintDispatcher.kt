package ymusic.quickjs

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlin.time.TimeSource

/**
 * Wrap [delegate] with the engine-thread watchdog (see [QuickJsRuntime.newDispatcher]). Two
 * complementary checks:
 *
 *  - **stint** ([onSlowStint]): one dispatched task held the thread for [stintMs]+ in one
 *    continuous stretch — blames the CULPRIT (an unchunked loop, a runaway regex, a blocking call
 *    that skipped its `withContext(IO)` hop).
 *  - **queue wait** ([onSlowWait]): a task waited [waitMs]+ between dispatch and running — the
 *    VICTIM's view, catching cumulative congestion no single stint shows (ten 20ms stints
 *    back-to-back delay the next task 200ms).
 *
 * The engine thread is a main thread (AGENTS.md): while it is held, every pipe, timer and command
 * stalls. Command wall time can't see any of this — it includes suspensions during which the
 * thread serves everyone else. Platform split only because the JVM's CloseableCoroutineDispatcher
 * is ExecutorCoroutineDispatcher-based.
 */
internal expect fun slowStintDispatcher(
    delegate: CloseableCoroutineDispatcher,
    stintMs: Long,
    onSlowStint: ((millis: Long) -> Unit)?,
    waitMs: Long,
    onSlowWait: ((millis: Long) -> Unit)?,
): CloseableCoroutineDispatcher

/** The shared watchdog clock around one dispatched task: queue wait since [enqueued], then the
 *  stint. Callback exceptions propagate on the engine thread — deliberate: the strict (fail-fast
 *  debug) callbacks throw to make a stall unmissable. */
internal inline fun runWatched(
    enqueued: TimeSource.Monotonic.ValueTimeMark,
    stintMs: Long,
    noinline onSlowStint: ((Long) -> Unit)?,
    waitMs: Long,
    noinline onSlowWait: ((Long) -> Unit)?,
    block: () -> Unit,
) {
    if (onSlowWait != null) {
        val waited = enqueued.elapsedNow().inWholeMilliseconds
        if (waited >= waitMs) onSlowWait(waited)
    }
    val mark = TimeSource.Monotonic.markNow()
    try {
        block()
    } finally {
        if (onSlowStint != null) {
            val ms = mark.elapsedNow().inWholeMilliseconds
            if (ms >= stintMs) onSlowStint(ms)
        }
    }
}
