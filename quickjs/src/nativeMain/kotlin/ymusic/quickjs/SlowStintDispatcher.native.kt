package ymusic.quickjs

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext

internal actual fun slowStintDispatcher(
    delegate: CloseableCoroutineDispatcher,
    thresholdMs: Long,
    onSlowStint: (millis: Long) -> Unit,
): CloseableCoroutineDispatcher = object : CloseableCoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        delegate.dispatch(context, Runnable { runStintTimed(thresholdMs, onSlowStint) { block.run() } })
    }

    override fun close() = delegate.close()
}
