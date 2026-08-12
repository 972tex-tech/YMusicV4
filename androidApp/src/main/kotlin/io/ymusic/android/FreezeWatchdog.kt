package io.ymusic.android

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

    /** Stamped by the composition-side ticker ([CompositionClockProbe]); 0 until it first runs. */
    @Volatile var compositionTickUptime = 0L

    /** The composition's own frame clock (the one the spinner actually animates on). */
    @Volatile var compositionClock: Any? = null

    fun start() {
        val main = Handler(Looper.getMainLooper())
        main.post { mainChoreographer = Choreographer.getInstance() }
        val clockScope = CoroutineScope(AndroidUiDispatcher.Main)
        thread(name = "freeze-watchdog", isDaemon = true) {
            var tick = 0
            var consecutiveClockStalls = 0
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
                // The clock the UI actually runs on: the window recomposer's (pausable) clock, fed
                // through the Recomposer — the spinner dies exactly when THIS stops ticking.
                val lastComposition = compositionTickUptime
                val compositionAge = if (lastComposition == 0L) -1 else android.os.SystemClock.uptimeMillis() - lastComposition
                // Healthy-idle grants can take ~2-3s (the Recomposer pumps lazily); a real stall
                // (spinner frozen) reads tens of seconds.
                val compositionStalled = compositionAge > 8_000
                val stalled = !looperOk || !choreoOk || !clockOk || compositionStalled
                if (stalled || tick % 10 == 0) {
                    val level = if (stalled) Log.ERROR else Log.INFO
                    Log.println(
                        level,
                        TAG,
                        "tick=$tick looper=${ok(looperOk)} choreographer=${ok(choreoOk)} " +
                            "dispatcherClock=${ok(clockOk)} compositionClockAgeMs=$compositionAge" +
                            (if (compositionStalled) " COMPOSITION-STALL" else "") +
                            " ${dispatcherState()} ${compositionClockState()}",
                    )
                }
                // The stranded-flag signature (choreographer alive, dispatcher clock dead with its
                // frame dispatch "scheduled" but the callback gone from the Choreographer queue —
                // the emulator's "Faking VSYNC due to driver stall" regime eats it): dump the
                // Choreographer's real queue state, then re-post the dispatcher's own callback —
                // its doFrame resets the flag and drains the parked waiters.
                consecutiveClockStalls = if (!clockOk) consecutiveClockStalls + 1 else 0
                // Heal only a PERSISTENT stall — a single missed probe is just the degraded
                // faked-vsync regime being slow, and it recovers on its own.
                if ((compositionStalled || consecutiveClockStalls >= 2) && choreoOk && looperOk) {
                    Log.e(TAG, "STRANDED-FLAG suspected; choreographer internals: ${choreographerState()}")
                    val healed = heal(main)
                    Log.e(TAG, "HEAL attempted=$healed")
                    if (healed) {
                        Thread.sleep(2_000)
                        val age = android.os.SystemClock.uptimeMillis() - compositionTickUptime
                        Log.e(TAG, "HEAL result=${if (age < 3_000) "RECOVERED" else "still-stalled"} compositionClockAgeMs=$age")
                        consecutiveClockStalls = 0
                    }
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

    /** The main Choreographer's internal queues: is the dispatcher's callback still queued
     *  (delayed vsync) or gone (consumed without running — the stranded-flag state)? */
    private fun choreographerState(): String = try {
        val choreo = mainChoreographer ?: return "choreographer=unset"
        val cls = choreo.javaClass
        fun field(name: String): Any? = cls.getDeclaredField(name).apply { isAccessible = true }.get(choreo)
        val queues = field("mCallbackQueues") as Array<*>
        val now = android.os.SystemClock.uptimeMillis()
        val entries = StringBuilder()
        queues.forEachIndexed { index, queue ->
            var record = queue!!.javaClass.getDeclaredField("mHead").apply { isAccessible = true }.get(queue)
            while (record != null) {
                val recordCls = record.javaClass
                val due = recordCls.getDeclaredField("dueTime").apply { isAccessible = true }.getLong(record)
                val action = recordCls.getDeclaredField("action").apply { isAccessible = true }.get(record)
                entries.append(" q$index:${action?.javaClass?.simpleName}@${due - now}ms")
                record = recordCls.getDeclaredField("next").apply { isAccessible = true }.get(record)
            }
        }
        "mFrameScheduled=${field("mFrameScheduled")} queued=[$entries ]"
    } catch (t: Throwable) {
        "choreographerState failed: $t"
    }

    @Volatile private var mainChoreographer: Choreographer? = null

    /** Re-post the dispatcher's own frame callback: doFrame -> performFrameDispatch resets the
     *  stranded scheduledFrameDispatch flag and drains the parked withFrameNanos waiters. Also
     *  breaks Choreographer's backward-timestamp wait loop (fake vsyncs with stale timestamps
     *  keep mFrameScheduled=true and never run callbacks): zeroing mLastFrameTimeNanos lets the
     *  next vsync pass the frameTimeNanos < mLastFrameTimeNanos check and drain the queues. */
    private fun heal(main: Handler): Boolean = try {
        val dispatcher = AndroidUiDispatcher.Main[ContinuationInterceptor] as Any
        val callback = dispatcher.javaClass.getDeclaredField("dispatchCallback")
            .apply { isAccessible = true }
            .get(dispatcher) as Choreographer.FrameCallback
        main.post {
            val choreographer = Choreographer.getInstance()
            runCatching {
                choreographer.javaClass.getDeclaredField("mLastFrameTimeNanos")
                    .apply { isAccessible = true }
                    .setLong(choreographer, 0L)
            }.onFailure { Log.e(TAG, "mLastFrameTimeNanos reset failed", it) }
            choreographer.postFrameCallback(callback)
        }
        true
    } catch (t: Throwable) {
        Log.e(TAG, "heal failed", t)
        false
    }

    /** Reflect whatever clock the composition handed us — a paused PausableMonotonicFrameClock
     *  (missed lifecycle resume) or a Recomposer BroadcastFrameClock with starving awaiters. */
    private fun compositionClockState(): String {
        val clock = compositionClock ?: return "compositionClock=unset"
        return try {
            val cls = clock.javaClass
            val bits = StringBuilder("compositionClock=${cls.simpleName}")
            for (name in listOf("isPaused", "hasAwaiters")) {
                runCatching {
                    val method = cls.methods.firstOrNull { it.name == name || it.name == "get${name.replaceFirstChar(Char::uppercase)}" }
                    if (method != null) bits.append(" $name=${method.invoke(clock)}")
                }
            }
            for (name in listOf("latch", "awaiters")) {
                runCatching {
                    val field = cls.getDeclaredField(name).apply { isAccessible = true }
                    val value = field.get(clock)
                    bits.append(" $name=${(value as? Collection<*>)?.size ?: value?.javaClass?.simpleName}")
                }
            }
            bits.toString()
        } catch (t: Throwable) {
            "compositionClockState failed: $t"
        }
    }
}

/** Mount once at the content root (covers the boot spinner too): stamps the watchdog every frame
 *  window on the composition's OWN frame clock and hands the clock object over for reflection. */
@androidx.compose.runtime.Composable
fun CompositionClockProbe() {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        FreezeWatchdog.compositionClock =
            kotlin.coroutines.coroutineContext[androidx.compose.runtime.MonotonicFrameClock]
        while (true) {
            withFrameNanos { FreezeWatchdog.compositionTickUptime = android.os.SystemClock.uptimeMillis() }
            kotlinx.coroutines.delay(500)
        }
    }
}
