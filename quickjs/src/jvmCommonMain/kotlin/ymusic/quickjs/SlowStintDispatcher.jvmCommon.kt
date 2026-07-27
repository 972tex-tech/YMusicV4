package ymusic.quickjs

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import java.util.concurrent.Executor
import kotlin.coroutines.CoroutineContext

internal actual fun slowStintDispatcher(
    delegate: CloseableCoroutineDispatcher,
    thresholdMs: Long,
    onSlowStint: (millis: Long) -> Unit,
): CloseableCoroutineDispatcher = object : ExecutorCoroutineDispatcher() {
    // newEngineDispatcher() is executor-backed on JVM/Android; expose its executor unchanged.
    override val executor: Executor get() = (delegate as ExecutorCoroutineDispatcher).executor

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        delegate.dispatch(context) { runStintTimed(thresholdMs, onSlowStint) { block.run() } }
    }

    override fun close() = delegate.close()
}
