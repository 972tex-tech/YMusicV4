package ymusic.quickjs

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlin.time.TimeSource

/**
 * Wrap [delegate] so every dispatched stint is timed, reporting the ones at or over [thresholdMs]
 * (see [QuickJsRuntime.newDispatcher]). The engine thread is a main thread (AGENTS.md): one long
 * synchronous stint — a runaway regex, an unchunked loop over a big document, a blocking platform
 * call that skipped its `withContext(IO)` hop — stalls every pipe, timer and command at once, and
 * nothing but per-stint timing can see it (a command's wall time includes suspensions, so it
 * proves nothing). Platform split only because the JVM's CloseableCoroutineDispatcher is
 * ExecutorCoroutineDispatcher-based.
 */
internal expect fun slowStintDispatcher(
    delegate: CloseableCoroutineDispatcher,
    thresholdMs: Long,
    onSlowStint: (millis: Long) -> Unit,
): CloseableCoroutineDispatcher

/** The shared stint clock: run [block], report to [onSlowStint] when it held for [thresholdMs]+.
 *  The callback's exceptions propagate out of the stint on the engine thread — deliberate: a
 *  strict (fail-fast debug) callback throws to make the stall unmissable. */
internal inline fun runStintTimed(thresholdMs: Long, noinline onSlowStint: (Long) -> Unit, block: () -> Unit) {
    val mark = TimeSource.Monotonic.markNow()
    try {
        block()
    } finally {
        val ms = mark.elapsedNow().inWholeMilliseconds
        if (ms >= thresholdMs) onSlowStint(ms)
    }
}
