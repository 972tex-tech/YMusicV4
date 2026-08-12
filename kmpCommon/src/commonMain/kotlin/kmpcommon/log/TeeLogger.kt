package kmpcommon.log

/** Fans every call out to two loggers — the shells' way to pair a reporting logger (Sentry) with
 *  the local platform echo (logcat / stdout) without either knowing about the other. */
class TeeLogger(private val first: Logger, private val second: Logger) : Logger {
    override val trail: List<Crumb> get() = first.trail

    override fun fork(tag: String, prop: Map<String, Any>?): Logger =
        TeeLogger(first.fork(tag, prop), second.fork(tag, prop))

    override fun forkAll(crumbs: List<Crumb>): Logger =
        if (crumbs.isEmpty()) this else TeeLogger(first.forkAll(crumbs), second.forkAll(crumbs))

    // Legs isolated: a throwing sink must neither skip the other leg nor throw INTO the error
    // path that was logging.
    override fun log(level: Logger.Level, message: String, error: Throwable?) {
        runCatching { first.log(level, message, error) }
        runCatching { second.log(level, message, error) }
    }

    override fun captureError(error: Throwable, message: String?, prop: Map<String, Any>?) {
        runCatching { first.captureError(error, message, prop) }
        runCatching { second.captureError(error, message, prop) }
    }
}
