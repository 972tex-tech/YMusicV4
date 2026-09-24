package io.ymusic.ios

import kmpcommon.log.Crumb
import kmpcommon.log.CrumbLogger
import kmpcommon.log.Logger
import platform.Foundation.NSLog

internal class IosLogger private constructor(crumbs: List<Crumb>) : CrumbLogger(crumbs) {
    constructor() : this(emptyList())

    override fun fork(crumbs: List<Crumb>): CrumbLogger = IosLogger(crumbs)

    override fun log(level: Logger.Level, message: String, error: Throwable?) {
        // NSLog lands in the simulator unified log (process iosApp). println does not.
        val line = "[ymusic][${level.name}] $path $message${error?.let { ": $it" }.orEmpty()}"
        NSLog("%@", line)
    }
}
