package io.ymusic.download

import io.ymusic.ytmedia.sabr.ClientInfo
import io.ymusic.ytmedia.sabr.SabrStreamSpec
import kmphttp.KmpHttpClient
import kmphttp.create
import kotlinx.coroutines.runBlocking
import projectcommon.error.TechnicalException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Fatal download failures carry an app error code (E7xxxx) so a user report maps to one throw site. */
class DownloadErrorCodeTest {

    private object ThrowingStorage : DownloadStorage {
        override fun scratch(taskId: String, name: String): DownloadScratch = throw UnsupportedOperationException()
        override fun deleteScratch(taskId: String) = Unit
        override suspend fun publish(scratch: DownloadScratch, fileName: String, destinationFolder: String): String = throw UnsupportedOperationException()
    }

    private fun spec(vararg formats: io.ymusic.ytmedia.sabr.AdaptiveFormat) = SabrStreamSpec(
        videoId = "vid", sabrUrl = "https://s/vp", ustreamerConfig = ByteArray(0),
        poToken = null, clientInfo = ClientInfo(), formats = formats.toList(),
    )

    private fun task(format: DownloadFormat) = DownloadTask(
        id = "t1", videoId = "vid", title = "T", artist = null, thumbnailUrl = null,
        format = format, requestedHeight = null, state = DownloadState.QUEUED,
        downloadedBytes = 0, totalBytes = 0, fileUri = null, errorMessage = null,
        createdAt = 0, updatedAt = 0,
    )

    private fun downloader(spec: SabrStreamSpec) = YtStreamDownloader(
        resolver = SabrSpecResolver { ResolvedVideo(spec) },
        client = KmpHttpClient.create {},
        userAgent = "test",
        storage = ThrowingStorage,
        mediaCache = null,
        segmentIndex = null,
        logger = null,
    )

    @Test
    fun no_audio_format_fails_with_E70011() = runBlocking {
        // A spec with no audio format — selectFormats throws before any I/O.
        val e = assertFailsWith<TechnicalException> {
            downloader(spec()).download(task(DownloadFormat.M4A), {}, {}) { _, _ -> }
        }
        assertEquals("E70011", e.code)
    }
}
