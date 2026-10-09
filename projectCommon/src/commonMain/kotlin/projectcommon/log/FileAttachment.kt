package projectcommon.log

/**
 * A local file [Logger.captureError] should attach by pathname so the blob is never copied onto
 * the heap.
 */
data class FileAttachment(
    val path: String,
    val filename: String,
    val contentType: String = "text/plain",
)
