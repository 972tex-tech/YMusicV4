package io.ymusic.quickjs

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

private val stintDumper: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "engine-stint-dump").apply { isDaemon = true }
    }

internal actual fun slowStintDispatcher(
    delegate: CloseableCoroutineDispatcher,
    stintMs: Long,
    onSlowStint: ((millis: Long, detail: String) -> Unit)?,
    waitMs: Long,
    onSlowWait: ((millis: Long) -> Unit)?,
): CloseableCoroutineDispatcher = object : ExecutorCoroutineDispatcher() {
    // newEngineDispatcher() is executor-backed on JVM/Android; expose its executor unchanged.
    override val executor: Executor get() = (delegate as ExecutorCoroutineDispatcher).executor

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val enqueued = TimeSource.Monotonic.markNow()
        val task = context[CoroutineName]?.name ?: "unnamed"
        delegate.dispatch(context) {
            val engineThread = Thread.currentThread()
            val liveDump = AtomicReference<String?>(null)
            val stillRunning = AtomicBoolean(true)
            val dump = if (onSlowStint != null && stintMs > 0L) {
                stintDumper.schedule(
                    {
                        if (stillRunning.get()) {
                            liveDump.set(formatEngineStack(task, engineThread))
                        }
                    },
                    stintMs,
                    TimeUnit.MILLISECONDS,
                )
            } else {
                null
            }
            try {
                runWatched(
                    enqueued,
                    stintMs,
                    onSlowStint?.let { cb ->
                        { ms: Long ->
                            cb(ms, liveDump.get() ?: "task=$task (stint ended before a live dump)")
                        }
                    },
                    waitMs,
                    onSlowWait,
                ) { block.run() }
            } finally {
                stillRunning.set(false)
                dump?.cancel(false)
            }
        }
    }

    override fun close() = delegate.close()
}

private fun formatEngineStack(task: String, thread: Thread): String = buildString {
    append("task=").append(task).append(" thread=").append(thread.name)
    for (el in thread.stackTrace) {
        append("\n  at ").append(el)
    }
}
