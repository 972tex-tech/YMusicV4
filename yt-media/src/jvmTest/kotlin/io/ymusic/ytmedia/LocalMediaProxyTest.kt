package io.ymusic.ytmedia

import io.ymusic.ytmedia.cache.DiskMediaBlobCache
import io.ymusic.ytmedia.cache.MediaCache
import io.ymusic.ytmedia.cache.SqlDelightBlobIndex
import io.ymusic.ytmedia.cache.SqlDelightSegmentIndex
import io.ymusic.ytmedia.cache.jvmSegmentDatabaseDriver
import io.ymusic.ytmedia.proxy.LocalMediaProxy
import io.ymusic.ytmedia.sabr.formatCacheKey
import kmpcommon.io.AsyncSource
import kmphttp.KmpHttpClient
import kmphttp.create
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The shared router: `serve()` maps a rewritten `/ymusicForward` request to a cache hit (the platform
 *  adapters — CefResourceHandler / shouldInterceptRequest / iOS message channel — all just call this). */
class LocalMediaProxyTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "ymusic-router-test").absolutePath
    private fun bytes(n: Int) = ByteArray(n) { (it and 0xFF).toByte() }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

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

        val health = proxy.serve("/health")
        assertEquals(200, health.status)

        val progUrl = "https://r1.googlevideo.com/videoplayback?itag=251&lmt=100&clen=40&range=0-39"
        val hit = proxy.serve("/ymusicForward?url=${enc(progUrl)}&method=GET")
        assertEquals(200, hit.status)
        assertContentEquals(bytes(40), drain(hit.body))
    }
}
