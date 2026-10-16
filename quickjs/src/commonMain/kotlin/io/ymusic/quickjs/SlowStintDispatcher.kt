package io.ymusic.quickjs

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlin.time.TimeSource

/**
 * Wrap [delegate] with the engine-thread watchdog (see [QuickJsRuntime.newDispatcher]). Two
 * complementary checks:
 *
 *  - **stint** ([onSlowStint]): one dispatched task held the thread for [stintMs]+ in one
 *    continuous stretch — blames the CULPRIT (an unchunked loop, a runaway regex, a blocking call
 *    that skipped its `withContext(IO)` hop). With [liveStacks] (JVM/Android), the [String] is a
 *    live engine-thread stack dump captured at the budget while the task is still running, and
 *    `pausedMillis` is how much of the stint the whole process was frozen (an OS freezer such as
 *    Samsung's Freecess stops every thread while the monotonic clock keeps counting); otherwise
 *    the detail is just the task name and `pausedMillis` is 0. Costs one scheduled job per task.
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
    onSlowStint: ((millis: Long, pausedMillis: Long, detail: String) -> Unit)?,
    waitMs: Long,
    onSlowWait: ((millis: Long) -> Unit)?,
    liveStacks: Boolean,
): CloseableCoroutineDispatcher

/** The shared watchdog clock around one dispatched task: queue wait since [enqueued], then the
 *  stint. Callback exceptions propagate on the engine thread — deliberate: the strict (fail-fast
 *  debug) callbacks throw to make a stall unmissable. */
internal inline fun runWatched(
    enqueued: TimeSource.Monotonic.ValueTimeMark,
    stintMs: Long,
    noinline onSlowStint: ((millis: Long, pausedMillis: Long) -> Unit)?,
    waitMs: Long,
    noinline onSlowWait: ((millis: Long) -> Unit)?,
    paused: () -> Long,
    block: () -> Unit,
) {
    val mark = TimeSource.Monotonic.markNow()
    if (onSlowWait != null) {
        val waited = (mark - enqueued).inWholeMilliseconds
        if (waited >= waitMs) onSlowWait(waited)
    }
    try {
        block()
    } finally {
        val pausedMs = paused()
        if (onSlowStint != null) {
            val ms = mark.elapsedNow().inWholeMilliseconds
            if (ms >= stintMs) onSlowStint(ms, pausedMs)
        }
    }
}
