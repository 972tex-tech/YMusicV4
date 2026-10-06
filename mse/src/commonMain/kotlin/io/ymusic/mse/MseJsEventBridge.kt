package io.ymusic.mse

/**
 * C/JNI → Kotlin hop for MSE host events. [setJsEventBridge] is `{ name -> engine.fire(name) }`;
 * [fire] is what native calls (any thread).
 */
object MseJsEventBridge {
    @kotlin.concurrent.Volatile
    private var handler: ((String) -> Unit)? = null

    fun setJsEventBridge(handler: (String) -> Unit) {
        this.handler = handler
    }

    fun fire(eventName: String) {
        handler?.invoke(eventName)
    }

    /** Clears only if [handler] is still installed: a late close must not unhook its successor. */
    fun clear(handler: (String) -> Unit) {
        if (this.handler === handler) this.handler = null
    }
}
