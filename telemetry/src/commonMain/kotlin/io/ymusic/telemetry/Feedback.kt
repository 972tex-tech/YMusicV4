package io.ymusic.telemetry

import io.sentry.kotlin.multiplatform.Attachment
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.protocol.UserFeedback

/**
 * Sends a user-authored feedback report as Sentry user feedback: an anchor message event carries
 * the attachments (feedback itself cannot), then the [UserFeedback] links to that event's id. The
 * anchor's message is "Feedback: " + the report's first line, and [fingerprintPolicy] groups one
 * Sentry issue per subject; `report_type:feedback` filters them from the error issues.
 * No-op (empty event id) until Sentry.init ran.
 */
fun sendSentryFeedback(
    comments: String,
    name: String? = null,
    email: String? = null,
    attachments: List<Attachment> = emptyList(),
) {
    val eventId = Sentry.captureMessage(feedbackSubject(comments)) { scope ->
        scope.setTag(REPORT_TYPE_TAG, REPORT_TYPE_FEEDBACK)
        attachments.forEach(scope::addAttachment)
    }
    Sentry.captureUserFeedback(
        UserFeedback(eventId).also {
            it.comments = comments
            it.name = name
            it.email = email
        },
    )
}

internal fun feedbackSubject(comments: String): String {
    val firstLine = comments.trim().lineSequence().first().trim()
    val subject = if (firstLine.length <= SUBJECT_MAX_CHARS) firstLine else firstLine.take(SUBJECT_MAX_CHARS).trimEnd() + "…"
    return "Feedback: $subject"
}

private const val SUBJECT_MAX_CHARS = 80

internal const val REPORT_TYPE_TAG = "report_type"
internal const val REPORT_TYPE_FEEDBACK = "feedback"
internal const val REPORT_TYPE_ERROR = "error"
