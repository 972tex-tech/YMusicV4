package ymusic.android

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.AndroidUiDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.ContinuationInterceptor

/**
 * Freeze-bug probe (board task 03d9c154): every few seconds, independently checks each layer that
 * could stall the UI — the main Looper, raw Choreographer frame callbacks, and Compose's frame
 * clock (AndroidUiDispatcher.Main) — and dumps the dispatcher's private scheduling state whenever
 * anything stalls. Diagnosis only; remove once the freeze is understood.
 */
object FreezeWatchdog {
    private const val TAG = "FreezeWatchdog"

    fun start() {
        val main = Handler(Looper.getMainLooper())
        val clockScope = CoroutineScope(AndroidUiDispatcher.Main)
        thread(name = "freeze-watchdog", isDaemon = true) {
            var tick = 0
            while (true) {
                Thread.sleep(3_000)
                tick++
                val looperOk = probe { latch -> main.post { latch.countDown() } }
                val choreoOk = probe { latch ->
                    main.post { Choreographer.getInstance().postFrameCallback { latch.countDown() } }
                }
                val clockOk = probe { latch ->
                    clockScope.launch {
                        withFrameNanos { }
                        latch.countDown()
                    }
                }
                val stalled = !looperOk || !choreoOk || !clockOk
                if (stalled || tick % 10 == 0) {
                    val level = if (stalled) Log.ERROR else Log.INFO
                    Log.println(
                        level,
                        TAG,
                        "tick=$tick looper=${ok(looperOk)} choreographer=${ok(choreoOk)} " +
                            "composeFrameClock=${ok(clockOk)} ${dispatcherState()}",
                    )
                }
            }
        }
    }

    private fun ok(value: Boolean) = if (value) "ok" else "STALL"

    private fun probe(arm: (CountDownLatch) -> Unit): Boolean {
        val latch = CountDownLatch(1)
        return try {
            arm(latch)
            latch.await(1_500, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            Log.e(TAG, "probe failed", t)
            false
        }
    }

    /** The dispatcher's private scheduling flags/queues — the leading-theory smoking gun. */
    private fun dispatcherState(): String = try {
        val dispatcher = AndroidUiDispatcher.Main[ContinuationInterceptor] as Any
        val cls = dispatcher.javaClass
        fun field(name: String): Any? = cls.getDeclaredField(name).apply { isAccessible = true }.get(dispatcher)
        val trampolined = (field("toRunTrampolined") as? Collection<*>)?.size
        val onFrame = (field("toRunOnFrame") as? Collection<*>)?.size
        "scheduledTrampolineDispatch=${field("scheduledTrampolineDispatch")} " +
            "scheduledFrameDispatch=${field("scheduledFrameDispatch")} " +
            "toRunTrampolined=$trampolined toRunOnFrame=$onFrame"
    } catch (t: Throwable) {
        "dispatcherState failed: $t"
    }
}
