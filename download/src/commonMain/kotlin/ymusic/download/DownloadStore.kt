package ymusic.download

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.withContext
import ymusic.download.db.DownloadDatabase
import ymusic.download.internal.downloadDbDispatcher

/**
 * Persistence for the download queue — a thin suspend facade over the generated SqlDelight queries, all
 * work confined to [downloadDbDispatcher]. The service is the single writer and keeps the live list in
 * memory; this only durably records it so the queue survives a restart. Build the [driver] with the
 * per-platform `downloadDatabase*Driver` factory.
 */
class DownloadStore(driver: SqlDriver) {

    private val queries = DownloadDatabase(driver).downloadTaskQueries

    suspend fun all(): List<DownloadTask> = withContext(downloadDbDispatcher()) {
        queries.selectAll(::mapRow).executeAsList()
    }

    suspend fun upsert(task: DownloadTask): Unit = withContext(downloadDbDispatcher()) {
        queries.upsert(
            id = task.id,
            video_id = task.videoId,
            title = task.title,
            artist = task.artist,
            thumbnail_url = task.thumbnailUrl,
            format = task.format.name,
            requested_height = task.requestedHeight?.toLong(),
            state = task.state.name,
            downloaded_bytes = task.downloadedBytes,
            total_bytes = task.totalBytes,
            file_path = task.filePath,
            error_message = task.errorMessage,
            created_at = task.createdAt,
            updated_at = task.updatedAt,
        )
    }

    suspend fun updateState(id: String, state: DownloadState, error: String?, updatedAt: Long): Unit =
        withContext(downloadDbDispatcher()) { queries.updateState(state.name, error, updatedAt, id) }

    suspend fun updateProgress(id: String, downloaded: Long, total: Long, updatedAt: Long): Unit =
        withContext(downloadDbDispatcher()) { queries.updateProgress(downloaded, total, updatedAt, id) }

    suspend fun updateCompleted(id: String, filePath: String, bytes: Long, updatedAt: Long): Unit =
        withContext(downloadDbDispatcher()) {
            queries.updateCompleted(DownloadState.COMPLETED.name, filePath, bytes, bytes, updatedAt, id)
        }

    suspend fun delete(id: String): Unit = withContext(downloadDbDispatcher()) { queries.deleteById(id) }

    @Suppress("LongParameterList")
    private fun mapRow(
        id: String,
        video_id: String,
        title: String,
        artist: String?,
        thumbnail_url: String?,
        format: String,
        requested_height: Long?,
        state: String,
        downloaded_bytes: Long,
        total_bytes: Long,
        file_path: String?,
        error_message: String?,
        created_at: Long,
        updated_at: Long,
    ) = DownloadTask(
        id = id,
        videoId = video_id,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnail_url,
        format = runCatching { DownloadFormat.valueOf(format) }.getOrDefault(DownloadFormat.M4A),
        requestedHeight = requested_height?.toInt(),
        state = runCatching { DownloadState.valueOf(state) }.getOrDefault(DownloadState.FAILED),
        downloadedBytes = downloaded_bytes,
        totalBytes = total_bytes,
        filePath = file_path,
        errorMessage = error_message,
        createdAt = created_at,
        updatedAt = updated_at,
    )
}
