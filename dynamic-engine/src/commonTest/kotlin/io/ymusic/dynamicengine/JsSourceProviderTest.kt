package io.ymusic.dynamicengine

import io.ymusic.dynamicengine.internal.ENGINE_TEST_JS
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JsSourceProviderTest {

    private val dir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ymusic-js-source-test"
    private var clock = 1_000L

    @BeforeTest
    @AfterTest
    fun clean() {
        FileSystem.SYSTEM.deleteRecursively(dir)
    }

    private fun provider(
        config: JsConfigProvider = JsConfigProvider { null },
        client: FakeHttpClient = FakeHttpClient(CannedResponse.ofText("")),
        embedVersion: Long = 5L,
        embedSource: suspend () -> String = { "embed-js" },
    ) = DefaultJsSourceProvider(
        client = client,
        config = config,
        embedVersion = embedVersion,
        embedSource = embedSource,
        fileSystem = FileSystem.SYSTEM,
        directory = dir,
        now = { clock },
    )

    @Test
    fun embed_serves_when_nothing_is_cached() = runTest {
        var reads = 0
        val p = provider(embedSource = { reads++; "embed-js" })
        assertNull(p.getCached())
        assertEquals("embed-js" to 5L, p.getEmbed())
        assertEquals("embed-js" to 5L, p.getEmbed())
        assertEquals(1, reads, "the embed asset loads lazily, once")
        assertEquals(0L, p.lastFetch())
    }

    @Test
    fun fetch_downloads_validates_and_caches() = runTest {
        val client = FakeHttpClient(CannedResponse.ofText("remote-js-v21"))
        val p = provider(config = { "https://cdn/x.js" to 21L }, client = client)
        clock = 7_777L
        val consumed = p.fetchIfAny(20L) { source, version -> "$source@$version" }
        assertEquals("remote-js-v21@21", consumed)
        assertEquals("remote-js-v21" to 21L, p.getCached())
        assertEquals(7_777L, p.lastFetch())
    }

    @Test
    fun a_rejected_bundle_caches_nothing_but_the_check_still_counts() = runTest {
        val client = FakeHttpClient(CannedResponse.ofText("broken-js"))
        val p = provider(config = { "https://cdn/x.js" to 21L }, client = client)
        clock = 3_333L
        assertFailsWith<IllegalStateException> {
            p.fetchIfAny(20L) { _, _ -> error("rejected by the boot gate") }
        }
        assertNull(p.getCached())
        assertEquals(3_333L, p.lastFetch())
    }

    @Test
    fun nothing_newer_downloads_nothing() = runTest {
        val client = FakeHttpClient(CannedResponse.ofText("unused"))
        val p = provider(config = { "https://cdn/x.js" to 20L }, client = client)
        assertNull(p.fetchIfAny(20L) { _, _ -> fail() })
        assertEquals(0, client.requestCount)
    }

    @Test
    fun absent_remote_config_returns_null_and_stamps_the_check() = runTest {
        val p = provider(config = { null })
        clock = 9_999L
        assertNull(p.fetchIfAny(20L) { _, _ -> fail() })
        assertEquals(9_999L, p.lastFetch())
    }

    @Test
    fun a_cache_older_than_the_embed_is_superseded() = runTest {
        val client = FakeHttpClient(CannedResponse.ofText("old-remote"))
        // Cache version 3 with an embed at version 2 — cached wins…
        val p3 = provider(config = { "https://cdn/x.js" to 3L }, embedVersion = 2L, client = client)
        p3.fetchIfAny(2L) { _, _ -> }
        assertEquals("old-remote" to 3L, p3.getCached())
        // …then the app updates and ships embed version 5: the stale cache reads as absent.
        val p5 = provider(embedVersion = 5L)
        assertNull(p5.getCached())
        assertEquals("embed-js" to 5L, p5.getEmbed())
    }

    private fun fail(): Nothing = kotlin.test.fail("must not be called")
}

/** The manager's provider path: cached boots (with the deadline), a rejected cache falls back to
 *  the embed, and checkForUpdates swaps a newer remote in through the full boot gate. */
class ManagerJsSourceTest {

    private class FakeSource(
        var cached: Pair<String, Long>? = null,
        val embed: Pair<String, Long> = ENGINE_TEST_JS to 20L,
        var remote: Pair<String, Long>? = null,
    ) : JsSourceProvider {
        var saved: Pair<String, Long>? = null
        override suspend fun getCached() = cached
        override suspend fun getEmbed() = embed
        override suspend fun lastFetch() = 0L
        override suspend fun <T> fetchIfAny(currentVersion: Long, consume: suspend (String, Long) -> T): T? {
            val (source, version) = remote ?: return null
            if (version <= currentVersion) return null
            val result = consume(source, version)
            saved = source to version
            return result
        }
    }

    private fun manager(source: JsSourceProvider) = DynamicEngineManager(
        client = FakeHttpClient(CannedResponse.ofText("")),
        appVersion = 1,
        jsSource = source,
        remoteBootTimeoutMs = 2_000L,
        updateCheckIntervalMs = 0L, // no background loop in tests — checkForUpdates() drives it
    )

    @Test
    fun start_boots_the_cached_bundle() = runTest {
        val m = manager(FakeSource(cached = ENGINE_TEST_JS to 21L))
        try {
            assertEquals(20, m.start()) // ENGINE_TEST_JS self-declares 20 regardless of cache tag
        } finally {
            m.close()
        }
    }

    @Test
    fun a_rejected_cache_falls_back_to_the_embed() = runTest {
        val m = manager(FakeSource(cached = "throw new Error('rotten cache')" to 21L))
        try {
            assertEquals(20, m.start())
        } finally {
            m.close()
        }
    }

    @Test
    fun checkForUpdates_swaps_in_a_newer_bundle_and_caches_it() = runTest {
        val source = FakeSource(remote = ENGINE_TEST_JS to 99L)
        val m = manager(source)
        try {
            m.start() // embed (nothing cached)
            assertEquals(20, m.checkForUpdates())
            assertEquals(ENGINE_TEST_JS to 99L, source.saved, "validated bundle is handed back for caching")
        } finally {
            m.close()
        }
    }
}
