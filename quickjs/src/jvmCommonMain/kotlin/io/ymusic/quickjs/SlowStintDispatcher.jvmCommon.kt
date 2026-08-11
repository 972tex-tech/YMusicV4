package io.ymusic.quickjs

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import java.util.concurrent.Executor
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

internal actual fun slowStintDispatcher(
    delegate: CloseableCoroutineDispatcher,
    stintMs: Long,
    onSlowStint: ((millis: Long) -> Unit)?,
    waitMs: Long,
    onSlowWait: ((millis: Long) -> Unit)?,
): CloseableCoroutineDispatcher = object : ExecutorCoroutineDispatcher() {
    // newEngineDispatcher() is executor-backed on JVM/Android; expose its executor unchanged.
    override val executor: Executor get() = (delegate as ExecutorCoroutineDispatcher).executor

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val enqueued = TimeSource.Monotonic.markNow()
        delegate.dispatch(context) {
            runWatched(enqueued, stintMs, onSlowStint, waitMs, onSlowWait) { block.run() }
        }
    }

    override fun close() = delegate.close()
}
