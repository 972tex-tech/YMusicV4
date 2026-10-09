package kmpcommon.log

/** Fans every call out to two loggers, e.g. a reporting sink and a local console echo. */
class TeeLogger(private val first: Logger, private val second: Logger) : Logger {
    override val trail: List<Crumb> get() = first.trail

    override fun fork(tag: String, prop: Map<String, Any>?): Logger =
        TeeLogger(first.fork(tag, prop), second.fork(tag, prop))

    override fun forkAll(crumbs: List<Crumb>): Logger =
        if (crumbs.isEmpty()) this else TeeLogger(first.forkAll(crumbs), second.forkAll(crumbs))

    // No leg isolation: a throwing sink is a programming error, not something to hide.
    override fun log(level: Logger.Level, message: String, error: Throwable?) {
        first.log(level, message, error)
        second.log(level, message, error)
    }

    override fun captureError(error: Throwable, message: String?, prop: Map<String, Any>?) {
        first.captureError(error, message, prop)
        second.captureError(error, message, prop)
    }
}
