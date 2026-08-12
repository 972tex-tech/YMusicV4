package kmpcommon.log

/**
 * One scope hop in a logger's trail. [tag] is a stable, code-location-like name
 * (`fetchHomeFeed`, `http`) — grouping keys are built from tag paths, so a variable tag silently
 * shatters crash grouping. Anything variable (ids, urls, params) goes in [prop], whose values must
 * be JSON primitives (String/Number/Boolean/null) — they cross the JS wire and Sentry serialization.
 */
data class Crumb(val tag: String, val prop: Map<String, Any?>? = null)

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
    fun fork(tag: String, prop: Map<String, Any?>? = null): Logger

    fun forkAll(crumbs: List<Crumb>): Logger

    fun log(level: Level, message: String, error: Throwable? = null)

    fun captureError(error: Throwable, message: String? = null, prop: Map<String, Any?>? = null) {
        log(Level.ERROR, message ?: error.message ?: error.toString(), error)
    }

    enum class Level { DEBUG, INFO, WARN, ERROR }
}

/** The trail bookkeeping every [Logger] shares: implementations supply [fork] (rebuild with a new
 *  crumb list) and the output side; [path] renders the trail for display/grouping. */
abstract class CrumbLogger protected constructor(
    protected val crumbs: List<Crumb>,
) : Logger {
    protected abstract fun fork(crumbs: List<Crumb>): CrumbLogger

    final override fun fork(tag: String, prop: Map<String, Any?>?): Logger = fork(crumbs + Crumb(tag, prop))

    final override fun forkAll(crumbs: List<Crumb>): Logger =
        if (crumbs.isEmpty()) this else fork(this.crumbs + crumbs)

    protected val path: String by lazy(LazyThreadSafetyMode.NONE) { crumbs.joinToString("/") { it.tag } }
}
