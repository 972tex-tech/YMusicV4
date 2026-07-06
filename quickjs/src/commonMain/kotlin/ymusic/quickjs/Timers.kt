package ymusic.quickjs

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import okio.Buffer

/**
 * `setTimeout` / `setInterval` (+ their `clear*`), **scheduled on the host**. QuickJS core ships no
 * timers — in every JS runtime they belong to the host (libuv in Node, the task scheduler in a
 * browser), not the engine. We provide them over the pipe protocol (no new native code), backed by a
 * coroutine `delay` on the engine scope: `delay` **suspends**, so the single engine thread stays free
 * while a timer waits, and the callback fires as a normal host→JS edge (like any pipe event) followed
 * by the microtask pump.
 *
 * Wire: JS opens a `"setTimeout"` / `"setInterval"` pipe, sends the delay (ms, as decimal text), then
 * receives one byte per fire; closing the pipe (`clearTimeout`/`clearInterval`) stops it. Reserves
 * those two command names.
 */
internal fun QuickJsRuntime.installTimers() {
    onAccept("setTimeout") { pipe -> runTimer(pipe, repeat = false) }
    onAccept("setInterval") { pipe -> runTimer(pipe, repeat = true) }
    // The JS globals (setTimeout/setInterval/clear*) live in the bootstrap bytecode blob.
}

private const val TICK = 1

/**
 * Read the delay, then fire after it — racing each wait against the pipe's close so a cleared timer
 * stops promptly and its `delay` is cancelled (no coroutine left sleeping). `setInterval` loops;
 * `setTimeout` fires once.
 */
private suspend fun runTimer(pipe: Pipe, repeat: Boolean) {
    val ms = readMillis(pipe)
    coroutineScope {
        val cleared = async { pipe.awaitClose() } // completes when JS closes the pipe (clear*)
        try {
            do {
                val stopped = select {
                    cleared.onAwait { true }   // cleared before the next tick → stop
                    onTimeout(ms) { false }    // elapsed → fire
                }
                if (stopped) break
                pipe.send(Buffer().writeByte(TICK), 1L)
            } while (repeat)
        } finally {
            cleared.cancel()
        }
    }
    pipe.close()
}

/** First frame carries the delay as decimal text. */
private suspend fun readMillis(pipe: Pipe): Long {
    val buf = Buffer()
    if (pipe.receive(buf, 32L) <= 0L) return 0
    return buf.readUtf8().trim().toLongOrNull()?.coerceAtLeast(0) ?: 0
}

/** Suspend until the peer closes the pipe (JS sends nothing after the delay). */
private suspend fun Pipe.awaitClose() {
    val b = Buffer()
    while (receive(b, 1L) >= 0L) b.clear() // wait for EOF
}
