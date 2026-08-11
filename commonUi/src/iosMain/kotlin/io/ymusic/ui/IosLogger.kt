package io.ymusic.ui

import kmpcommon.log.Logger

internal object IosLogger : Logger {
    override fun log(level: Logger.Level, tags: List<String>, message: String, error: Throwable?) {
        println("[ymusic][${level.name}] ${tags.joinToString("/")} $message${error?.let { ": $it" }.orEmpty()}")
    }
}
