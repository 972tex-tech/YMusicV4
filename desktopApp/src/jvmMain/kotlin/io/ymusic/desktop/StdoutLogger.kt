package io.ymusic.desktop

import kmpcommon.log.Logger

/** Desktop logging = stdout (the dev shell); tags render as the scope trail. */
object StdoutLogger : Logger {
    override fun log(level: Logger.Level, tags: List<String>, message: String, error: Throwable?) {
        println("[$level] ${tags.joinToString("/")}: $message")
        error?.printStackTrace()
    }
}
