package ymusic.ytmedia

import kmphttp.KmpHttpClient
import kmphttp.RedirectOptions
import kmphttp.RequestBuilder
import kmphttp.byteString
import kmphttp.create
import kmphttp.utf8String
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import ymusic.ytmedia.cache.DiskMediaBlobCache
import ymusic.ytmedia.cache.MediaCache
import ymusic.ytmedia.cache.SqlDelightSegmentIndex
import ymusic.ytmedia.cache.jvmSegmentDatabaseDriver
import ymusic.ytmedia.proxy.LocalMediaProxy
import ymusic.ytmedia.proxy.SocketMediaProxyServer
import ymusic.ytmedia.sabr.formatCacheKey
import java.io.File
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** End-to-end proxy on JVM: the socket transport + router serve a progressive range straight from the
 *  cache, and forward a non-media request through the http client. */
class MediaProxyServerTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "ymusic-proxy-test").absolutePath
    private fun bytes(n: Int) = ByteArray(n) { (it and 0xFF).toByte() }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    @Test
    fun serves_progressive_from_cache_and_forwards_misses() = runBlocking {
        FileSystem.SYSTEM.deleteRecursively(dir.toPath())
        val blobs = DiskMediaBlobCache(dir.toPath(), FileSystem.SYSTEM)
        val cache = MediaCache(blobs, SqlDelightSegmentIndex(jvmSegmentDatabaseDriver(), now = { 0 }))
        // Fill bytes [0,40) of stream itag=251,lmt=100 (what a progressive range read serves).
        withContext(Dispatchers.IO) {
            val w = blobs.writer(formatCacheKey(251, 100), 0, 40)
            w.sink.write(bytes(40)); w.commit()
        }

        val client = KmpHttpClient.create {}
        val proxy = LocalMediaProxy(cache, client, userAgent = "ymusic-test") { println("[proxy] $it") }
        val server = SocketMediaProxyServer(proxy)
        server.start()
        val origin = server.origin

        suspend fun get(url: String) = client.execute(
            RequestBuilder().url(url).get().build(),
            RedirectOptions(followRedirects = true, followSslRedirects = true),
        )

        // health
        val health = get("$origin/health")
        assertEquals(200, health.code)
        assertEquals("ymusic-proxy", health.body.utf8String())

        // progressive cache hit — the rewritten request webview-js would emit
        val progUrl = "https://r1.googlevideo.com/videoplayback?itag=251&lmt=100&clen=40&range=0-39"
        val hit = get("$origin/ymusicForward?url=${enc(progUrl)}&method=GET")
        assertEquals(200, hit.code)
        assertContentEquals(bytes(40), hit.body.byteString().toByteArray())

        // forward path (non-media) — proxy fetches the URL and passes bytes through
        val fwd = get("$origin/ymusicForward?url=${enc("$origin/health")}&method=GET")
        assertEquals(200, fwd.code)
        assertEquals("ymusic-proxy", fwd.body.utf8String())

        server.stop()
    }
}
