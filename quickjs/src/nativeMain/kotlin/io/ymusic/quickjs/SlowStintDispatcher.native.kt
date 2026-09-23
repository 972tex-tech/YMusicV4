package io.ymusic.quickjs

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

internal actual fun slowStintDispatcher(
    delegate: CloseableCoroutineDispatcher,
    stintMs: Long,
    onSlowStint: ((millis: Long, detail: String) -> Unit)?,
    waitMs: Long,
    onSlowWait: ((millis: Long) -> Unit)?,
): CloseableCoroutineDispatcher = object : CloseableCoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val enqueued = TimeSource.Monotonic.markNow()
        val fire = onSlowStint?.let { cb -> { ms: Long -> cb(ms, "") } }
        delegate.dispatch(
            context,
            Runnable { runWatched(enqueued, stintMs, fire, waitMs, onSlowWait) { block.run() } },
        )
    }

    override fun close() = delegate.close()
}
