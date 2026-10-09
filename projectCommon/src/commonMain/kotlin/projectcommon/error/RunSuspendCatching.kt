package projectcommon.error

import kotlin.coroutines.cancellation.CancellationException

/** [runCatching] for suspend code: cancellation is rethrown, never captured as a failure. */
inline fun <R> runSuspendCatching(block: () -> R): Result<R> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
