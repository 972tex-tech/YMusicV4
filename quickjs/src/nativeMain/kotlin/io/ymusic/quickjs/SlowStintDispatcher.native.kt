package io.ymusic.quickjs

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

internal actual fun slowStintDispatcher(
    delegate: CloseableCoroutineDispatcher,
    stintMs: Long,
    onSlowStint: ((millis: Long) -> Unit)?,
    waitMs: Long,
    onSlowWait: ((millis: Long) -> Unit)?,
): CloseableCoroutineDispatcher = object : CloseableCoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val enqueued = TimeSource.Monotonic.markNow()
        delegate.dispatch(
            context,
            Runnable { runWatched(enqueued, stintMs, onSlowStint, waitMs, onSlowWait) { block.run() } },
        )
    }

    override fun close() = delegate.close()
}
