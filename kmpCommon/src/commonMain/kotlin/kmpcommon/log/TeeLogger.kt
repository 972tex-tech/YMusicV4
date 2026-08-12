package kmpcommon.log

/** Fans every call out to two loggers — the shells' way to pair a reporting logger (Sentry) with
 *  the local platform echo (logcat / stdout) without either knowing about the other. */
class TeeLogger(private val first: Logger, private val second: Logger) : Logger {
    override val trail: List<Crumb> get() = first.trail

    override fun fork(tag: String, prop: Map<String, Any>?): Logger =
        TeeLogger(first.fork(tag, prop), second.fork(tag, prop))

    override fun forkAll(crumbs: List<Crumb>): Logger =
        if (crumbs.isEmpty()) this else TeeLogger(first.forkAll(crumbs), second.forkAll(crumbs))

    // No leg isolation on purpose: a logger sink must not throw, and one that does is a
    // programming error worth crashing on — swallowing it here would hide broken telemetry.
    override fun log(level: Logger.Level, message: String, error: Throwable?) {
        first.log(level, message, error)
        second.log(level, message, error)
    }

    override fun captureError(error: Throwable, message: String?, prop: Map<String, Any>?) {
        first.captureError(error, message, prop)
        second.captureError(error, message, prop)
    }
}
