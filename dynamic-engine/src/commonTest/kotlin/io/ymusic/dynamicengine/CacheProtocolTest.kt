package io.ymusic.dynamicengine

import kotlinx.coroutines.test.runTest
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The scoped cache protocol end to end: the bundle's `Cache` client (engine-js/src/protocol/cache.ts)
 * over the `cache/…` commands, served on a [DiskCacheStore] — an in-memory SQLite index over a real
 * `FileSystem.SYSTEM` temp dir for the blobs — with a deterministic ticking clock. Driven through the
 * test-bundle fixtures (engine-js/src/test/fixtures/cache.ts).
 */
class CacheProtocolTest {

    /** A fresh store: a new in-memory SQLite index + a wiped real temp blob dir; the clock ticks once
     *  per op so successive ops get distinct, ordered timestamps (for the eviction/lastUsed asserts). */
    private fun store(): DiskCacheStore {
        val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ymusic-cache-test"
        FileSystem.SYSTEM.deleteRecursively(dir)
        var tick = 1_000L
        return DiskCacheStore(testCacheDriver(), dir, FileSystem.SYSTEM, now = { tick++ })
    }

    private suspend fun DynamicEngine.drive(command: String, head: String): String {
        val pipe = openPipe(command)
        return try {
            val buf = okio.Buffer().writeUtf8(head)
            pipe.sink.send(buf, buf.size)
            val out = okio.Buffer()
            while (pipe.source.receive(out, 4096L) != -1L) { /* to EOF */ }
            out.readUtf8()
        } finally {
            pipe.sink.close()
            pipe.source.close()
        }
    }

    private suspend fun engine() = createTestEngine(FakeHttpClient(CannedResponse.ofText("")), cacheStore = store())

    private suspend fun check(command: String, head: String, expected: String) {
        val e = engine()
        try {
            assertEquals(expected, e.drive(command, head))
        } finally {
            e.close()
        }
    }

    @Test fun set_then_get_roundtrips_json_and_reports_size() = runTest {
        check("test/cache/set-get-json", """{"key":"ytcfg","value":{"a":"bc"}}""", """{"a":"bc"}|size=10""")
    }

    @Test fun set_then_get_roundtrips_text() = runTest {
        check("test/cache/set-get-text", """{"key":"k","value":"hello"}""", "hello")
    }

    @Test fun a_missing_key_reads_null() = runTest {
        check("test/cache/miss", """{"key":"nope"}""", "null")
    }

    @Test fun custom_meta_survives_put_and_list() = runTest {
        check("test/cache/custom-meta", """{"key":"k"}""", "abc,yt")
    }

    @Test fun put_sets_updated_equal_used_and_a_later_put_advances() = runTest {
        check("test/cache/put-timestamps", "{}", "true,true")
    }

    @Test fun get_bumps_last_used_past_updated() = runTest {
        check("test/cache/lastused-bumps", "{}", "true")
    }

    @Test fun scopes_are_isolated() = runTest {
        check("test/cache/scope-isolation", "{}", "a|b|null")
    }

    @Test fun delete_removes_the_entry() = runTest {
        check("test/cache/delete", """{"key":"k"}""", "null")
    }

    @Test fun keep_count_evictor_keeps_the_last_two_written() = runTest {
        check("test/cache/evict-keep-count", "{}", "b,c")
    }

    @Test fun lru_count_evictor_keeps_the_used_entry_alive() = runTest {
        check("test/cache/evict-lru-count", "{}", "a,c")
    }

    @Test fun lru_size_evictor_evicts_past_the_byte_budget() = runTest {
        check("test/cache/evict-lru-size", "{}", "b,c")
    }
}
