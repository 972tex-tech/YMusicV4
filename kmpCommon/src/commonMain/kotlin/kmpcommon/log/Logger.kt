package kmpcommon.log

/**
 * A logging sink the app wires to its platform logger (logcat / os_log / Crashlytics). Library code
 * logs through it; kmpCommon ships **no implementation** — the app provides one, and a `null`
 * `Logger` means logging is off.
 *
 * [tags] is a logical scope trail (e.g. `["download#a1b2", "js:resolve", "http"]`, CLAUDE.md
 * "Logging context") that says *what* the log belongs to; [error] carries the native cause so a
 * birth-site failure keeps its real stack. Nothing logged here is ever shown to the user — that's
 * the error code's job.
 */
interface Logger {
    fun log(level: Level, tags: List<String>, message: String, error: Throwable? = null)

    enum class Level { DEBUG, INFO, WARN, ERROR }
}
