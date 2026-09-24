package io.ymusic.ios

import kmpcommon.log.Crumb
import kmpcommon.log.CrumbLogger
import kmpcommon.log.Logger
import platform.posix.puts

internal class IosLogger private constructor(crumbs: List<Crumb>) : CrumbLogger(crumbs) {
    constructor() : this(emptyList())

    override fun fork(crumbs: List<Crumb>): CrumbLogger = IosLogger(crumbs)

    override fun log(level: Logger.Level, message: String, error: Throwable?) {
        // C string: NSLog("%@", kotlinString) SEGV — Kotlin String is not an NSObject.
        puts("[ymusic][${level.name}] $path $message${error?.let { ": $it" }.orEmpty()}")
    }
}
