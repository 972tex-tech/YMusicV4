package io.ymusic.android

import android.util.Log
import kmpcommon.log.Crumb
import kmpcommon.log.CrumbLogger
import kmpcommon.log.Logger

/** Routes library logs (engine host protocols, the bundle's console) to logcat under a "YMusic" tag. */
class LogcatLogger private constructor(crumbs: List<Crumb>) : CrumbLogger(crumbs) {
    constructor() : this(emptyList())

    override fun fork(crumbs: List<Crumb>): CrumbLogger = LogcatLogger(crumbs)

    override fun log(level: Logger.Level, message: String, error: Throwable?) {
        val text = if (crumbs.isEmpty()) message else path + ": " + message
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
