package io.ymusic.ui

/**
 * One user-picked feedback attachment. The shell's picker reads the bytes at pick time so the UI
 * (and the send path) never touches platform storage APIs; a file over [MAX_BYTES] comes back with
 * [bytes] = null (only its name and size), and the screen refuses it with a message instead of
 * holding an unbounded buffer.
 */
class FeedbackFile(
    val filename: String,
    val bytes: ByteArray?,
    val sizeBytes: Long = bytes?.size?.toLong() ?: 0L,
    val contentType: String? = null,
) {
    companion object {
        /** Per-file cap — well under Sentry's per-attachment envelope limit. */
        const val MAX_BYTES = 10L * 1024 * 1024
    }
}

/** A composed feedback report, handed as one value to the shell's [AppGraph.sendFeedback]. Every
 *  field except [message] is optional; [attachments] carry loaded bytes only. */
class FeedbackDraft(
    val message: String,
    val name: String?,
    val email: String?,
    val attachments: List<FeedbackFile>,
)
