package ymusic.download

/**
 * The container/codec a download is delivered as. The pipeline that produces each is in
 * [DownloadPipeline]; which are available depends on the platform (ffmpeg presence):
 *
 * - [MP3] / [M4A] — audio only. MP3 always transcodes (libmp3lame); M4A is copied as-is when the best
 *   audio is already AAC, otherwise transcoded. Both audio-only.
 * - [VIDEO_WEBM] — VP9 video + Opus audio, stream-copied into one WebM (cheap, no re-encode).
 * - [VIDEO_MP4] — video stream-copied + audio transcoded to AAC into one MP4 (broad device support).
 *
 * Every output goes through ffmpeg where it exists — not just to transcode/mux, but to embed the
 * metadata tags (title / artist / composer=videoId) into the container. iOS has no ffmpeg: there M4A
 * degrades to the raw best-AAC stream written straight to the file, and its metadata lives in the
 * download DB row instead of the file (video would need an AVFoundation muxer — separate). So
 * [needsFfmpeg] marks the outputs that are simply unavailable without ffmpeg; M4A stays available on
 * iOS (untagged file + DB metadata), the rest are hidden there.
 */
enum class DownloadFormat(val kind: DownloadKind, val fileExtension: String) {
    MP3(DownloadKind.AUDIO, "mp3"),
    M4A(DownloadKind.AUDIO, "m4a"),
    VIDEO_WEBM(DownloadKind.VIDEO, "webm"),
    VIDEO_MP4(DownloadKind.VIDEO, "mp4"),
    ;

    /** True when this output can't be produced at all without ffmpeg. M4A is the exception: it degrades
     *  to a raw-stream file (metadata in the DB) where ffmpeg is absent, so it stays available on iOS. */
    val needsFfmpeg: Boolean
        get() = when (this) {
            MP3 -> true // libmp3lame transcode
            M4A -> false // degrades to raw best-AAC + DB metadata on iOS; ffmpeg elsewhere embeds the tags
            VIDEO_WEBM, VIDEO_MP4 -> true // mux (and AAC transcode for mp4)
        }
}

enum class DownloadKind { AUDIO, VIDEO }

/**
 * Lifecycle of one download. Only [QUEUED], [PAUSED] and [FAILED] are resumable entry points; the
 * worker drives a task through [PREPARING] → [DOWNLOADING] → [PROCESSING] → [COMPLETED]. [CANCELED]
 * and [COMPLETED] are terminal.
 */
enum class DownloadState {
    /** Waiting for a worker. */
    QUEUED,

    /** Priming the SABR inputs (player response + PO token) — needs a live WebView. */
    PREPARING,

    /** Streaming the elementary track(s) to disk. [DownloadTask.downloadedBytes]/[totalBytes] advance. */
    DOWNLOADING,

    /** ffmpeg transcode/mux of the downloaded track(s) into the final container. */
    PROCESSING,

    /** Final file written and (if a library sink is set) registered. Terminal. */
    COMPLETED,

    /** User-paused; keeps any partially-downloaded bytes for resume. */
    PAUSED,

    /** Failed with [DownloadTask.errorMessage]; retryable. */
    FAILED,

    /** User-canceled; partial files cleaned up. Terminal. */
    CANCELED,
    ;

    val isTerminal: Boolean get() = this == COMPLETED || this == CANCELED
    val isActive: Boolean get() = this == PREPARING || this == DOWNLOADING || this == PROCESSING
}

/** What the caller asks to download; the [videoId] plus display metadata and the desired output. */
class DownloadRequest(
    val videoId: String,
    val title: String,
    val artist: String? = null,
    val thumbnailUrl: String? = null,
    val format: DownloadFormat,
    /** For [DownloadKind.VIDEO]: the target height (e.g. 1080); the nearest available is chosen. Null = best. */
    val requestedHeight: Int? = null,
)

/**
 * One row of the download queue — the persisted, observable unit the UI renders. Immutable snapshot;
 * the store emits a new instance on every change. [downloadedBytes]/[totalBytes] are the sum across
 * the (one or two) elementary tracks during [DownloadState.DOWNLOADING].
 */
class DownloadTask(
    val id: String,
    val videoId: String,
    val title: String,
    val artist: String?,
    val thumbnailUrl: String?,
    val format: DownloadFormat,
    val requestedHeight: Int?,
    val state: DownloadState,
    val downloadedBytes: Long,
    val totalBytes: Long,
    /** URI of the finished file once [state] is [DownloadState.COMPLETED]; null before. A platform URI —
     *  on Android a `content://` SAF document in one of the user's Library folders, a `file://`/path
     *  elsewhere — so it round-trips to the player and the library regardless of where the file lives. */
    val fileUri: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** 0f..1f, or null when the total isn't known yet (before the first MEDIA_HEADER). */
    val progress: Float?
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null

    fun copy(
        state: DownloadState = this.state,
        downloadedBytes: Long = this.downloadedBytes,
        totalBytes: Long = this.totalBytes,
        fileUri: String? = this.fileUri,
        errorMessage: String? = this.errorMessage,
        updatedAt: Long = this.updatedAt,
    ) = DownloadTask(
        id, videoId, title, artist, thumbnailUrl, format, requestedHeight,
        state, downloadedBytes, totalBytes, fileUri, errorMessage, createdAt, updatedAt,
    )
}
