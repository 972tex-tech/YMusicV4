package io.ymusic.mse

/**
 * C/JNI → Kotlin hop for critical native MSE/ffmpeg lines. [setLogBridge] is the
 * Logger sink; [fire] is what native calls (any thread). Must not block or throw.
 */
object MseLogBridge {
    @kotlin.concurrent.Volatile
    private var handler: ((Int, String) -> Unit)? = null

    fun setLogBridge(handler: (Int, String) -> Unit) {
        this.handler = handler
    }

    fun fire(level: Int, message: String) {
        try {
            handler?.invoke(level, message)
        } catch (_: Throwable) {
            // C decoder threads cannot unwind Kotlin.
        }
    }

    fun clear() {
        handler = null
    }
}
