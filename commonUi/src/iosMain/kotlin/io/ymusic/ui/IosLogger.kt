package io.ymusic.ui

import kmpcommon.log.Crumb
import kmpcommon.log.CrumbLogger
import kmpcommon.log.Logger

internal class IosLogger private constructor(crumbs: List<Crumb>) : CrumbLogger(crumbs) {
    constructor() : this(emptyList())

    override fun fork(crumbs: List<Crumb>): CrumbLogger = IosLogger(crumbs)

    override fun log(level: Logger.Level, message: String, error: Throwable?) {
        println("[ymusic][${level.name}] $path $message${error?.let { ": $it" }.orEmpty()}")
    }
}
