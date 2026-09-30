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

    fun clear() {
        handler = null
    }
}
