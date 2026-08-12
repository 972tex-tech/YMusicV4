package io.ymusic.desktop

import kmpcommon.log.Crumb
import kmpcommon.log.CrumbLogger
import kmpcommon.log.Logger

/** Desktop logging = stdout (the dev shell); the crumb trail renders as the path prefix. */
class StdoutLogger private constructor(crumbs: List<Crumb>) : CrumbLogger(crumbs) {
    constructor() : this(emptyList())

    override fun fork(crumbs: List<Crumb>): CrumbLogger = StdoutLogger(crumbs)

    override fun log(level: Logger.Level, message: String, error: Throwable?) {
        println("[$level] $path: $message")
        error?.printStackTrace()
    }
}

/** Probe logging: a line prefix and a minimum level to mute chatter below it. */
class PrefixLogger private constructor(
    private val prefix: String,
    private val min: Logger.Level,
    crumbs: List<Crumb>,
) : CrumbLogger(crumbs) {
    constructor(prefix: String, min: Logger.Level = Logger.Level.DEBUG) : this(prefix, min, emptyList())

    override fun fork(crumbs: List<Crumb>): CrumbLogger = PrefixLogger(prefix, min, crumbs)

    override fun log(level: Logger.Level, message: String, error: Throwable?) {
        if (level >= min) println("$prefix $level $path: $message" + (error?.let { " (${it.message})" } ?: ""))
    }
}
