package kmphttp.internal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.InterruptedIOException
import kotlin.coroutines.CoroutineContext

internal suspend fun <T> runIoInterruptible(
    // Dispatchers.IO, not the caller's: the block is a BLOCKING socket/okio read (main-safety rule).
    context: CoroutineContext = Dispatchers.IO,
    block: () -> T
): T = withContext(context) {
    val job = coroutineContext.job
    runInterruptible {
        try {
            block()
        } catch (e: InterruptedIOException) {
            // okio reports our cancel-interrupt as InterruptedIOException, which runInterruptible
            // doesn't recognize (kotlinx.coroutines#3551). A live job means a genuine timeout
            // (SocketTimeoutException, OkHttp call timeout): it must surface as the IOException it is.
            if (job.isActive) throw e
            throw InterruptedException().initCause(e)
        }
    }
}
