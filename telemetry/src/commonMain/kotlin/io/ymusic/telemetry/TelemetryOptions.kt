package io.ymusic.telemetry

import io.sentry.kotlin.multiplatform.SentryEvent
import io.sentry.kotlin.multiplatform.SentryOptions
import io.sentry.kotlin.multiplatform.protocol.Breadcrumb

/**
 * The Sentry options every app shell shares, applied inside its `Sentry.init` block:
 * breadcrumb depth (DEBUG chatter is cheap — breadcrumbs only ship attached to events),
 * [fingerprintPolicy], and the PII scrub backstop.
 */
fun SentryOptions.applyYMusicTelemetry() {
    maxBreadcrumbs = 200
    beforeBreadcrumb = ::scrubBreadcrumb
    beforeSend = ::fingerprintPolicy
}

/**
 * Grouping for [SentryLogger.captureError] events (async stacks make frame-based grouping
 * meaningless here): a coded error groups by its `E#####` code alone — one code, one throw site,
 * one issue; anything else groups by exception type + the capture's tag path, so the same
 * exception from two subsystems stays two issues. Events with no trail tag (uncaught crashes the
 * SDK captured itself) keep Sentry's default grouping.
 */
internal fun fingerprintPolicy(event: SentryEvent): SentryEvent {
    // TrailMark rides throws as a SUPPRESSED marker, but sentry-java serializes suppressed
    // exceptions into the visible chain (exception groups) — it is transport, never a report.
    if (event.exceptions.any { it.type == "TrailMark" }) {
        event.exceptions = event.exceptions.filterNot { it.type == "TrailMark" }.toMutableList()
    }
    val code = event.getTag(SentryLogger.CODE_TAG)
    val trail = event.getTag(SentryLogger.TRAIL_TAG)
    if (code != null) {
        event.fingerprint = mutableListOf(code)
    } else if (trail != null) {
        event.fingerprint = mutableListOf(event.exceptions.lastOrNull()?.type ?: "error", trail)
    }
    return event
}

// The one known value-bearing log shape: the cookie/http protocols DEBUG-log raw Set-Cookie
// strings (useful locally, never shippable). Names-only cookie logs pass through untouched.
private val SET_COOKIE = Regex("""set-cookie=\[[^\]]*]""", RegexOption.IGNORE_CASE)

internal fun scrubBreadcrumb(crumb: Breadcrumb): Breadcrumb {
    crumb.message?.let { crumb.message = SET_COOKIE.replace(it, "set-cookie=[redacted]") }
    return crumb
}
