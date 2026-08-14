package io.ymusic.telemetry

import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.SentryEvent
import io.sentry.kotlin.multiplatform.SentryOptions

/**
 * The Sentry options every app shell shares, applied inside its `Sentry.init` block. No PII
 * scrubbing: sensitive values (cookie values, SAPISIDHASH) are never put into log lines at the
 * source — names only.
 */
fun SentryOptions.applyYMusicTelemetry() {
    maxBreadcrumbs = 200
    beforeSend = ::fingerprintPolicy
    // Sentry Logs are OFF by default — without this every Sentry.logger call is dropped.
    logs.enabled = true
}

/**
 * Grouping for [SentryLogger.captureError] events (async stacks make frame-based grouping
 * meaningless here): a site code (`E#####`) groups alone — one code, one throw site, one issue;
 * a category code ("network", "parse", "oom") and a non-coded exception group per trail, so the
 * same failure shape in two subsystems stays two issues. Events with no trail tag (uncaught
 * crashes the SDK captured itself) keep Sentry's default grouping.
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
        // A site code (E#####) IS one throw site — it groups alone. Category codes ("network",
        // "parse", "oom") are shared by every subsystem, so they group per trail: a search timeout
        // and a download timeout are different issues.
        event.fingerprint =
            if (SITE_CODE.matches(code) || trail == null) mutableListOf(code) else mutableListOf(code, trail)
    } else if (trail != null) {
        event.fingerprint = mutableListOf(event.exceptions.lastOrNull()?.type ?: "error", trail)
    }
    return event
}

private val SITE_CODE = Regex("""E\d{5}""")

/** Attach the anonymous stable device id to every event this process sends (crash grouping across
 *  sessions, "how many devices" triage). No-op until Sentry.init ran. */
fun installSentryDeviceId(id: String) {
    Sentry.configureScope { scope -> scope.setContext("DEVICE_ID", id) }
}

/** Attach the RUNNING engine-js bundle's self-declared revision to every event — set at start and
 *  again on every remote swap (the manager's onBackendVersion callback), so a report always names
 *  the bundle that was live. A tag, not a context: tags are indexed (searchable, and issues show
 *  their value distribution), a context is display-only. */
fun installSentryEngineVersion(version: Int) {
    Sentry.configureScope { scope -> scope.setTag("engine.version", version.toString()) }
}
