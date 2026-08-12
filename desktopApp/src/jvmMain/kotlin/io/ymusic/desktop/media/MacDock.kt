package io.ymusic.desktop.media

import com.sun.jna.Callback
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import kmpcommon.log.Logger
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * macOS Dock-icon visibility: `NSApplication.setActivationPolicy` — Regular (normal Dock presence)
 * vs Accessory (no Dock icon, menu-bar/tray only, like Spotlight). AppKit wants this on the main
 * thread, and in a JVM app the AppKit thread is NOT the Swing EDT, so calls hop over via
 * `dispatch_async_f` onto the main queue (a plain C function pointer — no ObjC blocks needed);
 * AWT keeps that run loop alive. No-op off macOS; failures are swallowed (a Dock icon that stays
 * is cosmetic, never fatal).
 */
object MacDock {

    // Kept for the process-lifetime [drain] callback, which outlives any caller's scope.
    private var log: Logger? = null

    fun setDockIconVisible(visible: Boolean, logger: Logger? = null) {
        logger?.let { log = it }
        if ("mac" !in (System.getProperty("os.name")?.lowercase() ?: "")) return
        runCatching {
            onMainQueue {
                val app = objc.msgSendPointer(objc.cls("NSApplication"), "sharedApplication")!!
                objc.msgSendVoid(app, "setActivationPolicy:", if (visible) POLICY_REGULAR else POLICY_ACCESSORY)
                // Re-entering Regular leaves the app background-ordered until reactivated.
                if (visible) objc.msgSendVoid(app, "activateIgnoringOtherApps:", true)
            }
        }.onFailure { log?.log(Logger.Level.WARN, "MacDock unavailable", it) }
    }

    // --- main-queue trampoline: one static C callback draining a queue of pending blocks ---

    private val pending = ConcurrentLinkedQueue<() -> Unit>()

    private fun interface DispatchWork : Callback {
        fun invoke(context: Pointer?)
    }

    /** Referenced for the process lifetime — dispatch may fire after any narrower scope died. */
    private val drain = DispatchWork {
        pending.poll()?.let { block ->
            runCatching(block)
                .onFailure { log?.log(Logger.Level.WARN, "main-queue block failed", it) }
        }
    }

    private val system by lazy { NativeLibrary.getInstance("System") }
    private val objc by lazy { ObjCCalls() }

    private fun onMainQueue(block: () -> Unit) {
        pending.add(block)
        val mainQueue = system.getGlobalVariableAddress("_dispatch_main_q")
        system.getFunction("dispatch_async_f").invokeVoid(arrayOf(mainQueue, null, drain))
    }

    private class ObjCCalls {
        private val lib = NativeLibrary.getInstance("objc")
        private val msgSend = lib.getFunction("objc_msgSend")
        private val getClass = lib.getFunction("objc_getClass")
        private val selRegister = lib.getFunction("sel_registerName")

        fun cls(name: String): Pointer = getClass.invokePointer(arrayOf(name)) ?: error("no objc class $name")

        fun msgSendPointer(receiver: Pointer, selector: String, vararg args: Any?): Pointer? =
            msgSend.invokePointer(arrayOf(receiver, selRegister.invokePointer(arrayOf(selector)), *args))

        fun msgSendVoid(receiver: Pointer, selector: String, vararg args: Any?) {
            msgSend.invokeVoid(arrayOf(receiver, selRegister.invokePointer(arrayOf(selector)), *args))
        }
    }

    private const val POLICY_REGULAR = 0L
    private const val POLICY_ACCESSORY = 1L
}
