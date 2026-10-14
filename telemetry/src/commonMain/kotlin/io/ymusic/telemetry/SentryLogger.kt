package io.ymusic.telemetry

import io.sentry.kotlin.multiplatform.Attachment
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.SentryLevel
import io.sentry.kotlin.multiplatform.log.SentryLogLevel
import io.sentry.kotlin.multiplatform.protocol.Breadcrumb
import kmpcommon.log.Crumb
import kmpcommon.log.CrumbLogger
import kmpcommon.log.Logger
import projectcommon.error.codedError
import projectcommon.log.FileAttachment
import projectcommon.log.trailMark

/**
 * The app's root [Logger] over Sentry: an INFO+ [log] is a Sentry Log (attributes = the crumb-tag
 * path + the trail's merged props), every [captureError] a Sentry event (the "network" and
 * "captcha" categories excepted: a WARN log + breadcrumb) whose grouping comes from the coded error — else exception
 * type + tag path — applied by [applyYMusicTelemetry]'s `beforeSend` off the tags set here
 * (`Scope` exposes no fingerprint).
 */
class SentryLogger private constructor(crumbs: List<Crumb>) : CrumbLogger(crumbs) {

    constructor() : this(emptyList())

    override fun fork(crumbs: List<Crumb>): CrumbLogger = SentryLogger(crumbs)

    /** The trail's props flattened root→leaf (leaf wins) — every log line carries them as
     *  attributes, which is how one job's lines stay filterable next to a concurrent twin's. */
    private val props: Map<String, Any> by lazy(LazyThreadSafetyMode.NONE) {
        val merged = mutableMapOf<String, Any>()
        for (c in crumbs) c.prop?.let(merged::putAll)
        merged
    }

    override fun log(level: Logger.Level, message: String, error: Throwable?) {
        // DEBUG stays in the local log file, which ships only with a user's feedback report.
        if (level == Logger.Level.DEBUG) return
        val body = buildString {
            append(message)
            if (error != null) {
                append("\nError: ")
                append(error.stackTraceToString().take(STACK_CAP))
            }
        }
        // Trail + props ride as structured attributes (typed setters), not message prefixes —
        // that is what Sentry Logs can search and filter on.
        Sentry.logger.log(level.toSentryLogLevel()) {
            message(body)
            attributes {
                set(TRAIL_TAG, path)
                for ((k, v) in props) {
                    when (v) {
                        is String -> set(k, v)
                        is Boolean -> set(k, v)
                        is Int -> set(k, v)
                        is Long -> set(k, v)
                        is Float -> set(k, v)
                        is Double -> set(k, v)
                        else -> set(k, v.toString())
                    }
                }
            }
        }
    }

    override fun captureError(error: Throwable, message: String?, prop: Map<String, Any>?) {
        // The deepest trail stamped in flight (Throwable.markTrail), else this catch site's.
        val trail = error.trailMark() ?: crumbs
        val trailPath = trail.joinToString("/") { it.tag }
        val coded = error.codedError()
        // The user's connectivity or YouTube's bot challenge, not a defect: the UI already shows the
        // localized category, so it must not count against crash-free rate — a WARN log + breadcrumb only.
        val userCategory = coded?.code?.takeIf { it in USER_CATEGORIES }
        if (userCategory != null) {
            SentryLogger(trail).log(Logger.Level.WARN, message ?: error.message ?: error.toString(), error)
            Sentry.addBreadcrumb(
                Breadcrumb(
                    level = SentryLevel.WARNING,
                    category = userCategory,
                    message = "$trailPath: ${error.message}",
                ),
            )
            return
        }
        Sentry.captureException(error) { scope ->
            scope.setTag(TRAIL_TAG, trailPath)
            coded?.code?.let { scope.setTag(CODE_TAG, it) }
            // The trail rendered as this event's closing breadcrumbs — one row per hop, props as
            // data — right where the reader already is, in step with every other crumb.
            for (c in trail) scope.addBreadcrumb(Breadcrumb(message = c.tag, data = c.prop?.let(::HashMap)))
            message?.let { scope.setExtra("capture.message", it) }
            prop?.forEach { (k, v) ->
                when (v) {
                    is FileAttachment -> scope.addAttachment(Attachment(v.path, v.filename, v.contentType))
                    is ByteArray -> scope.addAttachment(Attachment(v, k))
                    else -> scope.setContext("capture.$k", v)
                }
            }
        }
    }

    private fun Logger.Level.toSentryLogLevel(): SentryLogLevel = when (this) {
        Logger.Level.DEBUG -> SentryLogLevel.DEBUG
        Logger.Level.INFO -> SentryLogLevel.INFO
        Logger.Level.WARN -> SentryLogLevel.WARN
        Logger.Level.ERROR -> SentryLogLevel.ERROR
    }

    internal companion object {
        internal const val TRAIL_TAG = "trail"
        internal const val CODE_TAG = "error.code"
        private val USER_CATEGORIES = setOf("network", "captcha")
        private const val STACK_CAP = 2000
    }
}
