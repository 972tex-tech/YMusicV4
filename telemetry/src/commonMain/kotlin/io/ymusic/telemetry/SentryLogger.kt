package io.ymusic.telemetry

import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.SentryLevel
import io.sentry.kotlin.multiplatform.protocol.Breadcrumb
import kmpcommon.error.codedError
import kmpcommon.log.Crumb
import kmpcommon.log.CrumbLogger
import kmpcommon.log.Logger
import kmpcommon.log.trailMark

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
        for (c in crumbs) c.prop?.let(merged::putAll)
        merged
    }

    override fun log(level: Logger.Level, message: String, error: Throwable?) {
        echo?.invoke(level, if (crumbs.isEmpty()) message else "$path: $message", error)
        val data = if (props.isEmpty() && error == null) {
            null
        } else {
            HashMap<String, Any>(props).also {
                // The birth-site log carries the native stack; a capped copy on the breadcrumb is
                // what survives once the failure crosses a boundary as {code, message}.
                if (error != null) it["error"] = error.stackTraceToString().take(STACK_CAP)
            }
        }
        Sentry.addBreadcrumb(Breadcrumb(level = level.toSentry(), message = message, category = path, data = data))
    }

    override fun captureError(error: Throwable, message: String?, prop: Map<String, Any>?) {
        // The deepest trail stamped in flight (Throwable.markTrail), else this catch site's.
        val trail = error.trailMark() ?: crumbs
        val trailPath = trail.joinToString("/") { it.tag }
        echo?.invoke(Logger.Level.ERROR, "$trailPath: capture ${message ?: error.message ?: error}", error)
        val coded = error.codedError()
        Sentry.captureException(error) { scope ->
            scope.setTag(TRAIL_TAG, trailPath)
            coded?.code?.let { scope.setTag(CODE_TAG, it) }
            // The trail rendered as this event's closing breadcrumbs — one row per hop, props as
            // data — right where the reader already is, in step with every other crumb.
            for (c in trail) scope.addBreadcrumb(Breadcrumb(message = c.tag, data = c.prop?.let(::HashMap)))
            message?.let { scope.setExtra("capture.message", it) }
            prop?.forEach { (k, v) -> scope.setExtra("capture.$k", v.toString()) }
        }
    }

    // Sentry's ERROR level is reserved for captured events (the fatal record); breadcrumbs top
    // out at WARNING so the timeline never shouts louder than the event it decorates.
    private fun Logger.Level.toSentry(): SentryLevel = when (this) {
        Logger.Level.DEBUG -> SentryLevel.DEBUG
        Logger.Level.INFO -> SentryLevel.INFO
        Logger.Level.WARN, Logger.Level.ERROR -> SentryLevel.WARNING
    }

    internal companion object {
        internal const val TRAIL_TAG = "trail"
        internal const val CODE_TAG = "error.code"
        private const val STACK_CAP = 2000
    }
}
