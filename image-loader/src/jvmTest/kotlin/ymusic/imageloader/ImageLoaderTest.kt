package ymusic.imageloader

import androidx.compose.ui.graphics.ImageBitmap
import kmphttp.AsyncInterceptor
import kmphttp.Cache
import kmphttp.CookieJar
import kmphttp.KmpHttpClient
import kmphttp.Protocol
import kmphttp.RedirectOptions
import kmphttp.Request
import kmphttp.Response
import kmphttp.ResponseBuilder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.IOException
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration

private class FakeImageServer(
    private val imageBytes: ByteArray,
    private val status: Int = 200,
) : KmpHttpClient {
    var fetchCount = 0
        private set

    override val interceptors: List<AsyncInterceptor> = emptyList()
    override val networkInterceptors: List<AsyncInterceptor> = emptyList()
    override val cache: Cache? = null
    override val cookieJar: CookieJar = CookieJar.NO_COOKIES
    override val sslConfig = kmphttp.SslConfig()
    override val defaultRedirectOptions = RedirectOptions(followRedirects = true, followSslRedirects = true)
    override val defaultExecuteTimeout: Duration? = null
    override val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default

    override suspend fun execute(request: Request, options: RedirectOptions, timeout: Duration?): Response {
        fetchCount++
        return ResponseBuilder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message(if (status == 200) "OK" else "ERR")
            .body(imageBytes.toResponseBody(null))
            .build()
    }

    override fun newBuilder(): KmpHttpClient.BaseBuilder<out KmpHttpClient> = throw UnsupportedOperationException()
}

private fun pngBytes(width: Int, height: Int): ByteArray {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.color = Color.ORANGE
    g.fillRect(0, 0, width, height)
    g.dispose()
    val out = ByteArrayOutputStream()
    ImageIO.write(img, "png", out)
    return out.toByteArray()
}

class ImageLoaderTest {

    @Test
    fun decodesScaledDownToTheRequestedSize() = runTest {
        val server = FakeImageServer(pngBytes(400, 200))
        val loader = ImageLoader(server)
        // Requests bucket up to 64: a 400x200 source into 64x64 → 64x32 (aspect kept).
        val bitmap = loader.load("https://img.test/a.png", 50, 50)
        assertEquals(64, bitmap.width)
        assertEquals(32, bitmap.height)
    }

    @Test
    fun smallImagesAreNeverUpscaled() = runTest {
        val server = FakeImageServer(pngBytes(40, 20))
        val loader = ImageLoader(server)
        val bitmap = loader.load("https://img.test/small.png", 500, 500)
        assertEquals(40, bitmap.width)
        assertEquals(20, bitmap.height)
    }

    @Test
    fun memoryCacheServesRepeatLoads() = runTest {
        val server = FakeImageServer(pngBytes(100, 100))
        val loader = ImageLoader(server)
        val first = loader.load("https://img.test/a.png", 64, 64)
        val second = loader.load("https://img.test/a.png", 64, 64)
        assertEquals(1, server.fetchCount, "second load must come from the memory cache")
        assertTrue(first === second, "must be the same cached instance")
    }

    @Test
    fun scaledCacheSkipsFetchAcrossLoaderInstances() = runTest {
        val fs = FakeFileSystem()
        val cache = DiskScaledImageCache(fs, "/img".toPath())
        val serverA = FakeImageServer(pngBytes(400, 200))
        ImageLoader(serverA, scaledCache = cache).load("https://img.test/a.png", 64, 64)
        assertEquals(1, serverA.fetchCount)

        // A fresh loader (cold memory cache) over the same disk cache: no fetch, same scaled size.
        val serverB = FakeImageServer(pngBytes(400, 200))
        val warm = ImageLoader(serverB, scaledCache = cache).load("https://img.test/a.png", 64, 64)
        assertEquals(0, serverB.fetchCount, "scaled cache must serve the warm load")
        assertEquals(64, warm.width)
        assertEquals(32, warm.height)
    }

    @Test
    fun sourceCacheSharesOneDownloadAcrossSizes() = runTest {
        val fs = FakeFileSystem()
        val src = ImageSourceCache(fs, "/src".toPath())
        val server = FakeImageServer(pngBytes(400, 200))
        ImageLoader(server, sourceCache = src).load("https://img.test/a.png", 64, 64)
        assertEquals(1, server.fetchCount)

        // A different size bucket on a cold loader decodes from the on-disk original — no refetch.
        val warm = ImageLoader(server, sourceCache = src).load("https://img.test/a.png", 200, 200)
        assertEquals(1, server.fetchCount, "source cache must serve other sizes without a refetch")
        assertEquals(256, warm.width) // 200 buckets up to 256; 400x200 scaled to fit 256x256
    }

    @Test
    fun sourceCachePathRejectsOversizedBody() = runTest {
        val fs = FakeFileSystem()
        val src = ImageSourceCache(fs, "/src".toPath())
        val server = FakeImageServer(pngBytes(400, 200))
        val loader = ImageLoader(server, sourceCache = src, maxImageBytes = 64)
        assertFailsWith<IOException> { loader.load("https://img.test/a.png", 64, 64) }
    }

    @Test
    fun oversizedBodyRejectedInsteadOfBuffered() = runTest {
        val server = FakeImageServer(pngBytes(400, 200))
        val loader = ImageLoader(server, maxImageBytes = 64)
        assertFailsWith<IOException> { loader.load("https://img.test/huge.png", 64, 64) }
    }

    @Test
    fun unscaledResultIsNotPersisted() = runTest {
        val fs = FakeFileSystem()
        val cache = DiskScaledImageCache(fs, "/img".toPath())
        val serverA = FakeImageServer(pngBytes(40, 20))
        ImageLoader(serverA, scaledCache = cache).load("https://img.test/small.png", 64, 64)
        assertEquals(1, serverA.fetchCount)

        // Nothing was shrunk, so nothing may be persisted: a cold loader must fetch again
        // (the HTTP cache already holds the original; a PNG re-encode would only waste disk).
        val serverB = FakeImageServer(pngBytes(40, 20))
        ImageLoader(serverB, scaledCache = cache).load("https://img.test/small.png", 64, 64)
        assertEquals(1, serverB.fetchCount, "an un-downscaled image must not be served from the scaled cache")
    }

    @Test
    fun cacheKeySharesEntriesAcrossChangedUrls() = runTest {
        val server = FakeImageServer(pngBytes(100, 100))
        val loader = ImageLoader(server)
        val stable = "https://img.test/vi/abc/hq720.jpg"
        val a = loader.load("$stable?sig=1", 64, 64, cacheKey = stable)
        val b = loader.load("$stable?sig=2", 64, 64, cacheKey = stable)
        assertEquals(1, server.fetchCount, "a rotated URL with the same cacheKey must hit the cache")
        assertTrue(a === b)
        assertTrue(loader.peek("$stable?sig=3", 64, 64, cacheKey = stable) === a)
    }

    @Test
    fun peekIsSynchronousMemoryHit() = runTest {
        val server = FakeImageServer(pngBytes(100, 100))
        val loader = ImageLoader(server)
        assertEquals(null, loader.peek("https://img.test/a.png", 64, 64))
        val bitmap = loader.load("https://img.test/a.png", 64, 64)
        assertTrue(loader.peek("https://img.test/a.png", 64, 64) === bitmap)
        assertEquals(null, loader.peek("https://img.test/a.png", 500, 500), "other sizes stay misses")
    }

    @Test
    fun trimAndBudgetEvictTheMemoryCache() = runTest {
        val server = FakeImageServer(pngBytes(100, 100))
        val loader = ImageLoader(server)
        val url = "https://img.test/a.png"
        loader.load(url, 64, 64)

        loader.trimMemoryCache(0f) // pressure: clear, budget unchanged
        assertEquals(null, loader.peek(url, 64, 64))
        loader.load(url, 64, 64)
        assertTrue(loader.peek(url, 64, 64) != null, "cache regrows after a transient trim")

        loader.setMemoryCacheBudget(0)
        assertEquals(null, loader.peek(url, 64, 64))
        loader.load(url, 64, 64)
        assertEquals(null, loader.peek(url, 64, 64), "a zero budget keeps nothing")
    }

    @Test
    fun httpFailureThrows() = runTest {
        val server = FakeImageServer(pngBytes(10, 10), status = 404)
        val loader = ImageLoader(server)
        assertFailsWith<IOException> { loader.load("https://img.test/missing.png", 64, 64) }
    }

    @Test
    fun undecodableBodyThrows() = runTest {
        val server = FakeImageServer("not an image".encodeToByteArray())
        val loader = ImageLoader(server)
        assertFailsWith<IllegalArgumentException> { loader.load("https://img.test/junk.png", 64, 64) }
    }

    @Test
    fun diskCacheEvictsLeastRecentlyUsedWhenOverBudget() = runTest {
        val fs = FakeFileSystem()
        val warmup = DiskScaledImageCache(fs, "/img".toPath())
        warmup.put("aa", ImageBitmap(8, 8))
        warmup.put("bb", ImageBitmap(8, 8))
        warmup.get("aa") // refreshes "aa" — LRU, not FIFO: "bb" is now the eviction candidate
        val total = warmup.sizeBytes
        warmup.close()

        // Reopen one byte under the total: exactly the least-recently-used entry must go.
        val cache = DiskScaledImageCache(fs, "/img".toPath(), maxBytes = total - 1)
        cache.trimNow()
        assertEquals(null, cache.get("bb"))
        assertEquals(8, cache.get("aa")?.width)
    }
}
