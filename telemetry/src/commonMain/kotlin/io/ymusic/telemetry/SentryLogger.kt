package io.ymusic.telemetry

import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.SentryLevel
import io.sentry.kotlin.multiplatform.protocol.Breadcrumb
import kmpcommon.error.codedError
import kmpcommon.log.Crumb
import kmpcommon.log.CrumbLogger
import kmpcommon.log.Logger
import kmpcommon.log.resolveTrail

/**
 * The app's root [Logger] over Sentry: every [log] is a breadcrumb (category = the crumb-tag path,
 * data = the trail's merged props), every [captureError] a Sentry event whose grouping comes from
 * the coded error — else exception type + tag path — applied by [applyYMusicTelemetry]'s
 * `beforeSend` off the tags set here (`Scope` exposes no fingerprint).
 *
 * [echo] mirrors every line to the platform logger (logcat / stdout) — Sentry breadcrumbs are
 * invisible during local dev. Safe with Sentry uninitialized (empty DSN): the SDK no-ops and only
 * the echo remains.
 */
class SentryLogger private constructor(
    private val echo: ((Logger.Level, String, Throwable?) -> Unit)?,
    crumbs: List<Crumb>,
) : CrumbLogger(crumbs) {

    constructor(echo: ((Logger.Level, String, Throwable?) -> Unit)? = null) : this(echo, emptyList())

    override fun fork(crumbs: List<Crumb>): CrumbLogger = SentryLogger(echo, crumbs)

    /** The trail's props flattened root→leaf (leaf wins) — every breadcrumb carries them, which is
     *  how one job's lines stay separable from a concurrent twin's (natural ids over minted ones). */
    private val props: MutableMap<String, Any> by lazy(LazyThreadSafetyMode.NONE) {
        val merged = mutableMapOf<String, Any>()
        for (c in crumbs) c.prop?.forEach { (k, v) -> if (v != null) merged[k] = v }
        merged
    }

    override fun log(level: Logger.Level, message: String, error: Throwable?) {
        echo?.invoke(level, if (crumbs.isEmpty()) message else "$path: $message", error)
        val crumb = Breadcrumb(level = level.toSentry(), message = message, category = path)
        if (props.isNotEmpty() || error != null) {
            // Copied per crumb: setData may keep the reference, and `props` is shared by every log
            // on this logger.
            val data = HashMap<String, Any>(props)
            // The birth-site log carries the native stack; keep a capped copy on the breadcrumb so
            // the eventual capture (which crossed a boundary as {code, message}) still shows it.
            if (error != null) data["error"] = error.stackTraceToString().take(STACK_CAP)
            crumb.setData(data)
        }
        Sentry.addBreadcrumb(crumb)
    }

    override fun captureError(error: Throwable, message: String?, prop: Map<String, Any?>?) {
        // A stamped trail extending this catch site wins (same tree, deeper); one from a different
        // lineage is appended after it — the catch site's own context must survive either way.
        val trail = error.resolveTrail(crumbs)
        val trailPath = trail.joinToString("/") { it.tag }
        echo?.invoke(Logger.Level.ERROR, "$trailPath: capture ${message ?: error.message ?: error}", error)
        val coded = error.codedError()
        Sentry.captureException(error) { scope ->
            scope.setTag(TRAIL_TAG, trailPath)
            coded?.code?.let { scope.setTag(CODE_TAG, it) }
            scope.setContext("trail", trail.map { c -> c.tag + (c.prop?.let { " $it" } ?: "") })
            message?.let { scope.setExtra("capture.message", it) }
            prop?.forEach { (k, v) -> if (v != null) scope.setExtra("capture.$k", v.toString()) }
        }
    }

    private fun Logger.Level.toSentry(): SentryLevel = when (this) {
        Logger.Level.DEBUG -> SentryLevel.DEBUG
        Logger.Level.INFO -> SentryLevel.INFO
        Logger.Level.WARN -> SentryLevel.WARNING
        Logger.Level.ERROR -> SentryLevel.ERROR
    }

    internal companion object {
        internal const val TRAIL_TAG = "trail"
        internal const val CODE_TAG = "error.code"
        private const val STACK_CAP = 2000
    }
}
