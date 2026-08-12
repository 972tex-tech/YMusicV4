package kmpcommon.log

/**
 * One scope hop in a logger's trail. [tag] is a stable, code-location-like name
 * (`fetchHomeFeed`, `http`) — grouping keys are built from tag paths, so a variable tag silently
 * shatters crash grouping. Anything variable (ids, urls, params) goes in [prop], whose values must
 * be JSON primitives (String/Number/Boolean) — they cross the JS wire and Sentry serialization.
 */
data class Crumb(val tag: String, val prop: Map<String, Any>? = null)

/**
 * An immutable scope-trail logger the app wires to its platform sink (logcat / stdout / Sentry).
 * Library code logs through it; kmpCommon ships **no implementation** — the app provides one, and a
 * `null` `Logger` means logging is off.
 *
 * [fork] snapshots this trail plus one hop and returns a new logger — the trail is the *logical
 * async stack* (AGENTS.md "Logging context"): each layer forks its own hop and hands the child down,
 * so a birth-site log deep in a host protocol carries the caller's whole story. Forking emits
 * nothing; only [log] does. A job origin should put a natural id in the fork's prop
 * (`fork("task", mapOf("id" to task.id))`) so concurrent jobs' logs stay separable.
 *
 * [log] is a breadcrumb: WARN at birth sites with the real [Throwable] (full native fidelity — a
 * stringified error round-tripped across a boundary has lost its stack). [captureError] is the one
 * crash record of a FAILED job — called exactly once, in the frame that declares the job dead; a
 * failure the caller recovers from must never be captured (crash-free rate). Nothing logged here is
 * ever shown to the user — that's the error code's job (AGENTS.md "Error Handling").
 */
interface Logger {
    /** The scope trail this logger carries (root → leaf). */
    val trail: List<Crumb>

    fun fork(tag: String, prop: Map<String, Any>? = null): Logger

    fun forkAll(crumbs: List<Crumb>): Logger

    fun log(level: Level, message: String, error: Throwable? = null)

    fun captureError(error: Throwable, message: String? = null, prop: Map<String, Any>? = null) {
        log(Level.ERROR, message ?: error.message ?: error.toString(), error)
    }

    enum class Level { DEBUG, INFO, WARN, ERROR }
}

/** The trail bookkeeping every [Logger] shares: implementations supply [fork] (rebuild with a new
 *  crumb list) and the output side; [path] renders the trail for display/grouping. */
abstract class CrumbLogger protected constructor(
    protected val crumbs: List<Crumb>,
) : Logger {
    final override val trail: List<Crumb> get() = crumbs

    protected abstract fun fork(crumbs: List<Crumb>): CrumbLogger

    final override fun fork(tag: String, prop: Map<String, Any>?): Logger = fork(crumbs + Crumb(tag, prop))

    final override fun forkAll(crumbs: List<Crumb>): Logger =
        if (crumbs.isEmpty()) this else fork(this.crumbs + crumbs)

    protected val path: String by lazy(LazyThreadSafetyMode.NONE) { crumbs.joinToString("/") { it.tag } }
}

/**
 * A trail riding a throw: the logger holds the story but a [Throwable] unwinds the stack without
 * it, so the frame that lets an exception escape stamps its trail on as a suppressed marker —
 * the value channel (code/message/cause) stays untouched, and [Logger.captureError] implementations
 * prefer the stamped trail over the catch site's shallower one. The message renders the path so
 * the marker stays readable wherever suppressed exceptions surface.
 */
class TrailMark internal constructor(val trail: List<Crumb>) :
    Exception(trail.joinToString("/") { it.tag })

/** Stamp [logger]'s trail onto this in-flight throwable. First mark wins — the deepest frame
 *  stamps first as the stack unwinds (the deepest-wins rule, like `Throwable.codedError`). */
fun Throwable.markTrail(logger: Logger?): Throwable {
    if (logger != null && suppressedExceptions.none { it is TrailMark }) {
        addSuppressed(TrailMark(logger.trail))
    }
    return this
}

/** The deepest trail stamped anywhere on this throwable's cause chain, or null. */
fun Throwable.trailMark(): List<Crumb>? {
    var deepest: List<Crumb>? = null
    var t: Throwable? = this
    var hops = 0
    while (t != null && hops++ < 100) {
        for (s in t.suppressedExceptions) if (s is TrailMark) deepest = s.trail
        t = t.cause
    }
    return deepest
}

/**
 * The global switch for [debug] call sites. Shells set it once at startup from their build type
 * (Android: `BuildConfig.DEBUG`, iOS: `Platform.isDebugBinary`). On a minified Android release
 * build, proguard-rules.pro pins the getter false via `-assumevalues`, so R8 constant-folds every
 * inlined check and strips the message-building code from the binary — removed, not silently
 * dropped.
 */
object LogConfig {
    var debugLogs: Boolean = true
}

/** A DEBUG line whose message is built ONLY while debug logging is on — the form for chatty paths
 *  (per-request lines, per-part transfer chatter). */
inline fun Logger?.debug(message: () -> String) {
    if (this != null && LogConfig.debugLogs) log(Logger.Level.DEBUG, message())
}
