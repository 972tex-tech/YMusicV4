package io.ymusic.ytmedia

import io.ymusic.ytmedia.cache.DiskMediaBlobCache
import io.ymusic.ytmedia.cache.MediaCache
import io.ymusic.ytmedia.cache.SqlDelightBlobIndex
import io.ymusic.ytmedia.cache.SqlDelightSegmentIndex
import io.ymusic.ytmedia.cache.jvmSegmentDatabaseDriver
import io.ymusic.ytmedia.proto.ProtoReader
import io.ymusic.ytmedia.proxy.LocalMediaProxy
import io.ymusic.ytmedia.proxy.SabrProxyHttpClient
import io.ymusic.ytmedia.sabr.BufferedRange
import io.ymusic.ytmedia.sabr.ClientAbrState
import io.ymusic.ytmedia.sabr.ClientInfo
import io.ymusic.ytmedia.sabr.FormatId
import io.ymusic.ytmedia.sabr.MediaHeader
import io.ymusic.ytmedia.sabr.StreamerContext
import io.ymusic.ytmedia.sabr.VideoPlaybackAbrRequest
import io.ymusic.ytmedia.sabr.formatCacheKey
import io.ymusic.ytmedia.ump.UmpPart
import io.ymusic.ytmedia.ump.readUmpPartHeader
import io.ymusic.ytmedia.ump.writeUmpPart
import io.ymusic.ytmedia.ump.writeUmpPartHeader
import kmpcommon.io.AsyncSource
import kmphttp.AsyncInterceptor
import kmphttp.Cache
import kmphttp.CookieJar
import kmphttp.HeadersBuilder
import kmphttp.KmpHttpClient
import kmphttp.Protocol
import kmphttp.RedirectOptions
import kmphttp.Request
import kmphttp.RequestBuilder
import kmphttp.Response
import kmphttp.ResponseBuilder
import kmphttp.SslConfig
import kmphttp.byteString
import kmphttp.create
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/** The media router: [LocalMediaProxy.serveRequest] answers a cached request without the network. */
class LocalMediaProxyTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "ymusic-router-test").absolutePath
    private fun bytes(n: Int) = ByteArray(n) { (it and 0xFF).toByte() }

    private suspend fun drain(body: AsyncSource): ByteArray {
        val out = Buffer()
        val buf = Buffer()
        while (true) {
            val n = body.read(buf, 16 * 1024)
            if (n == -1L) break
            out.write(buf, buf.size)
        }
        body.close()
        return out.readByteArray()
    }

    @Test
    fun serves_a_progressive_range_from_cache() = runBlocking {
        FileSystem.SYSTEM.deleteRecursively(dir.toPath())
        val driver = jvmSegmentDatabaseDriver()
        val blobs = DiskMediaBlobCache(dir.toPath(), FileSystem.SYSTEM, SqlDelightBlobIndex(driver, now = { 0 }))
        val cache = MediaCache(blobs, SqlDelightSegmentIndex(driver, now = { 0 }))
        withContext(Dispatchers.IO) {
            val w = blobs.writer(formatCacheKey(251, 100), 0, 40)
            w.sink.write(bytes(40)); w.commit()
        }
        val proxy = LocalMediaProxy(cache, KmpHttpClient.create {}, userAgent = "ymusic-test")

        val progUrl = "https://r1.googlevideo.com/videoplayback?itag=251&lmt=100&clen=40&range=0-39"
        val hit = proxy.serveRequest(progUrl, "GET", body = null)
        assertEquals(200, hit.status)
        assertContentEquals(bytes(40), drain(hit.body))
    }

    private val dummyDir = File(System.getProperty("java.io.tmpdir"), "ymusic-router-dummy").absolutePath
    private val audioId = FormatId(140, 100, "")
    private val ctx = StreamerContext(ClientInfo(clientName = 1), byteArrayOf(1), ByteArray(0))

    private fun emptyCache(): MediaCache {
        FileSystem.SYSTEM.deleteRecursively(dummyDir.toPath())
        val driver = jvmSegmentDatabaseDriver()
        return MediaCache(
            DiskMediaBlobCache(dummyDir.toPath(), FileSystem.SYSTEM, SqlDelightBlobIndex(driver, now = { 0 })),
            SqlDelightSegmentIndex(driver, now = { 0 }),
        )
    }

    private fun abrRequest(audioOnly: Boolean = true) = VideoPlaybackAbrRequest(
        clientAbrState = ClientAbrState(
            playerTimeMs = 10_000,
            enabledTrackTypesBitfield = if (audioOnly) 1 else 0,
        ),
        selectedFormats = emptyList(),
        bufferedRanges = listOf(BufferedRange(audioId, 0, 50_000, 0, 50)),
        selectedAudioFormats = listOf(audioId),
        selectedVideoFormats = listOf(FormatId(243, 1, "")),
        streamerContext = ctx,
        ustreamerConfig = ByteArray(0),
        videoId = "vid",
    )

    private fun audioUmp(): ByteArray {
        val audioData = ByteArray(64) { 7 }
        return Buffer().apply {
            val header = MediaHeader(
                headerId = 1, videoId = "vid", itag = 140, lmt = 100, xtag = null,
                startRange = 0, isInitSegment = false, segmentIndex = 60,
                startMs = 50_000, durationMs = 10_000, contentLength = audioData.size.toLong(),
            ).encode()
            writeUmpPart(UmpPart.MEDIA_HEADER, header)
            writeUmpPartHeader(UmpPart.MEDIA_DATA, audioData.size + 1L).writeByte(1).write(audioData)
            writeUmpPartHeader(UmpPart.MEDIA_END, 1).writeByte(1)
        }.readByteArray()
    }

    private fun containsItag(ump: ByteArray, itag: Int): Boolean {
        val buf = Buffer().write(ump)
        while (!buf.exhausted()) {
            val h = buf.readUmpPartHeader()
            if (h.partId == UmpPart.MEDIA_HEADER) {
                val mh = MediaHeader.from(ProtoReader(buf.readByteArray(h.size)).readAllFields())
                if (mh.itag == itag) return true
            } else {
                buf.skip(h.size)
            }
        }
        return false
    }

    private fun umpHttp(request: Request, bytes: ByteArray): Response = ResponseBuilder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .headers(
            HeadersBuilder()
                .add("Content-Type", "application/vnd.yt-ump")
                .add("Content-Length", bytes.size.toString())
                .build(),
        )
        .body(Buffer().write(bytes).asResponseBody(null, bytes.size.toLong()))
        .build()

    private class CannedHttp(
        private val handler: suspend (Request) -> Response,
    ) : KmpHttpClient {
        override var cookieJar: CookieJar = CookieJar.NO_COOKIES
        override val interceptors: List<AsyncInterceptor> = emptyList()
        override val networkInterceptors: List<AsyncInterceptor> = emptyList()
        override val cache: Cache? = null
        override val sslConfig: SslConfig = SslConfig()
        override val defaultRedirectOptions: RedirectOptions =
            RedirectOptions(followRedirects = true, followSslRedirects = true)
        override val defaultExecuteTimeout: Duration? = null
        override val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
        override val logger: kmpcommon.log.Logger? = null

        override suspend fun execute(request: Request, options: RedirectOptions, timeout: Duration?): Response =
            handler(request)

        override fun newBuilder(): KmpHttpClient.BaseBuilder<out KmpHttpClient> =
            throw UnsupportedOperationException("CannedHttp has no builder")
    }

    @Test
    fun sabr_miss_audio_only_passes_googlevideo_body() = runBlocking {
        val origin = audioUmp()
        val proxy = LocalMediaProxy(emptyCache(), CannedHttp { umpHttp(it, origin) }, userAgent = "ymusic-test")
        val resp = proxy.serveRequest(
            "https://r1.googlevideo.com/videoplayback?sabr=1",
            "POST",
            abrRequest().encode().buffer,
        )
        assertEquals(200, resp.status)
        val bytes = drain(resp.body)
        assertContentEquals(origin, bytes)
        assertTrue(containsItag(bytes, 140))
        assertTrue(!containsItag(bytes, 0))
    }

    @Test
    fun sabr_live_audio_only_does_not_append_dummy() = runBlocking {
        val origin = audioUmp()
        val proxy = LocalMediaProxy(emptyCache(), CannedHttp { umpHttp(it, origin) }, userAgent = "ymusic-test")
        val resp = proxy.serveRequest(
            "https://r1.googlevideo.com/videoplayback?sabr=1&live=1",
            "POST",
            abrRequest().encode().buffer,
        )
        assertEquals(200, resp.status)
        val bytes = drain(resp.body)
        assertContentEquals(origin, bytes)
        assertTrue(!containsItag(bytes, 0))
    }

    @Test
    fun sabr_miss_av_keeps_body_and_content_length() = runBlocking {
        val origin = audioUmp()
        val proxy = LocalMediaProxy(emptyCache(), CannedHttp { umpHttp(it, origin) }, userAgent = "ymusic-test")
        val resp = proxy.serveRequest(
            "https://r1.googlevideo.com/videoplayback?sabr=1",
            "POST",
            abrRequest(audioOnly = false).encode().buffer,
        )
        assertEquals(origin.size.toLong(), resp.contentLength)
        val bytes = drain(resp.body)
        assertContentEquals(origin, bytes)
        assertTrue(!containsItag(bytes, 0))
    }

    @Test
    fun sabr_proxy_http_client_does_not_inject_dummy() = runBlocking {
        val origin = audioUmp()
        val canned = CannedHttp { umpHttp(it, origin) }
        val proxy = LocalMediaProxy(emptyCache(), canned, userAgent = "ymusic-test")
        val client = SabrProxyHttpClient(canned, proxy)
        val req = RequestBuilder()
            .url("https://r1.googlevideo.com/videoplayback?sabr=1")
            .method("POST", abrRequest().encode().toRequestBody(null))
            .build()
        val resp = client.execute(req)
        val bytes = resp.body.byteString().toByteArray()
        assertContentEquals(origin, bytes)
        assertTrue(!containsItag(bytes, 0))
    }
}
