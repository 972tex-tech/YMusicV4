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
 * On a platform without ffmpeg (iOS) only the no-mux, no-transcode outputs are possible — see
 * [needsFfmpeg]. There the app offers M4A audio (progressive best-AAC) and MP4 video only when a
 * progressive/muxable-without-ffmpeg source exists; everything else is hidden.
 */
enum class DownloadFormat(val kind: DownloadKind, val fileExtension: String) {
    MP3(DownloadKind.AUDIO, "mp3"),
    M4A(DownloadKind.AUDIO, "m4a"),
    VIDEO_WEBM(DownloadKind.VIDEO, "webm"),
    VIDEO_MP4(DownloadKind.VIDEO, "mp4"),
    ;

    /** True when producing this output requires ffmpeg (transcode or mux). */
    val needsFfmpeg: Boolean
        get() = when (this) {
            MP3 -> true // libmp3lame transcode
            M4A -> false // best-effort: copied when source is AAC (checked at run time), else needs ffmpeg
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
    /** Absolute path of the finished file once [state] is [DownloadState.COMPLETED]; null before. */
    val filePath: String?,
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
        filePath: String? = this.filePath,
        errorMessage: String? = this.errorMessage,
        updatedAt: Long = this.updatedAt,
    ) = DownloadTask(
        id, videoId, title, artist, thumbnailUrl, format, requestedHeight,
        state, downloadedBytes, totalBytes, filePath, errorMessage, createdAt, updatedAt,
    )
}
