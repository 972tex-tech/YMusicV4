package ymusic.ui

import kmpcommon.uri.Uri
import ymusic.download.DownloadFormat
import ymusic.download.DownloadPipeline
import ymusic.download.DownloadTask
import ymusic.mediastore.BetterMediaStore
import ymusic.mediastore.model.MediaItemDraft
import ymusic.settings.SettingsStore

/**
 * Register a finished download into the local library so it shows up in the Library. Wired as the
 * `DownloadService.onCompleted` hook by the shells. The file lives in the user's download-location
 * folder (a media-store bucket), so we upsert a [MediaItemDraft] under that bucket keyed on the file uri
 * — no rescan needed. No-op when there's no download location (the file went to the app default dir,
 * which isn't a Library folder).
 */
suspend fun registerCompletedDownload(
    mediaStore: BetterMediaStore,
    settings: SettingsStore,
    task: DownloadTask,
    result: DownloadPipeline.Result,
) {
    val folder = settings.downloadLocationFolder() ?: return
    val bucket = mediaStore.findBucketByUri(Uri.parse(folder)) ?: return
    mediaStore.upsertItem(
        bucketId = bucket.id,
        draft = MediaItemDraft(
            uri = Uri.parse(result.uri),
            title = task.title,
            mimeType = task.format.mimeType(),
            fileSize = result.bytes,
            lastModified = task.updatedAt,
            artist = task.artist,
            durationMs = result.audio.approxDurationMs.toLong().takeIf { it > 0 },
            width = result.video?.width,
            height = result.video?.height,
            youtubeVid = task.videoId,
        ),
    )
}

private fun DownloadFormat.mimeType(): String = when (this) {
    DownloadFormat.MP3 -> "audio/mpeg"
    DownloadFormat.M4A -> "audio/mp4"
    DownloadFormat.VIDEO_MP4 -> "video/mp4"
    DownloadFormat.VIDEO_WEBM -> "video/webm"
}
