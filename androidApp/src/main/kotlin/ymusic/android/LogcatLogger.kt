package ymusic.android

import android.util.Log
import kmpcommon.log.Logger

/** Routes library logs (engine host protocols, the bundle's console) to logcat under a "YMusic" tag. */
class LogcatLogger : Logger {
    override fun log(level: Logger.Level, tags: List<String>, message: String, error: Throwable?) {
        val text = if (tags.isEmpty()) message else tags.joinToString("/") + ": " + message
        when (level) {
            Logger.Level.DEBUG -> Log.d(TAG, text, error)
            Logger.Level.INFO -> Log.i(TAG, text, error)
            Logger.Level.WARN -> Log.w(TAG, text, error)
            Logger.Level.ERROR -> Log.e(TAG, text, error)
        }
    }

    private companion object {
        const val TAG = "YMusic"
    }
}
