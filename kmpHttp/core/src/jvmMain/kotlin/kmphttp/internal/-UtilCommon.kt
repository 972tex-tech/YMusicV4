package kmphttp.internal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.InterruptedIOException
import kotlin.coroutines.CoroutineContext

internal suspend fun <T> runIoInterruptible(
    // Default to Dispatchers.IO, not EmptyCoroutineContext: the block does a BLOCKING socket/okio
    // read, so with the empty default it ran on the caller's dispatcher despite the "Io" in the
    // name — response.body.utf8String() from Dispatchers.Main blocked the main thread (ANR). The
    // project rule is that suspend functions are main-safe.
    context: CoroutineContext = Dispatchers.IO,
    block: () -> T
): T {
    return runInterruptible(context) {
        try {
            block()
        } catch (e: InterruptedIOException) {
            // https://github.com/Kotlin/kotlinx.coroutines/issues/3551
            throw InterruptedException().initCause(e)
        }
    }
}
