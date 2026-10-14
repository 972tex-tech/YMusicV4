package io.ymusic.telemetry

import io.sentry.kotlin.multiplatform.Attachment
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.protocol.UserFeedback

/**
 * Sends a user-authored feedback report as Sentry user feedback: an anchor message event carries
 * the attachments (feedback itself cannot), then the [UserFeedback] links to that event's id. The
 * anchor's constant message keeps every report in ONE Sentry issue ([fingerprintPolicy] leaves
 * non-error events on default message grouping); individual reports live in the Feedback tab.
 * No-op (empty event id) until Sentry.init ran.
 */
fun sendSentryFeedback(
    comments: String,
    name: String? = null,
    email: String? = null,
    attachments: List<Attachment> = emptyList(),
) {
    val eventId = Sentry.captureMessage("User feedback") { scope ->
        scope.setTag(FEEDBACK_TAG, "true")
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

internal const val FEEDBACK_TAG = "feedback"
