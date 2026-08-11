package io.ymusic.dynamicengine

import kmpcommon.io.AsyncSource
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import okio.use
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Apple factory over a real sqliter database on a real temp directory — the platform half the
 * shared [DiskCacheStore] tests can't cover (driver creation, `basePath`, directory creation).
 */
class AppleEngineCacheStoreTest {

    private val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ymusic-apple-enginecache-test"

    @AfterTest
    fun cleanup() {
        FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
    }

    private fun sourceOf(text: String): AsyncSource = object : AsyncSource {
        private val buf = Buffer().writeUtf8(text)
        override suspend fun read(sink: Buffer, byteCount: Long): Long =
            if (buf.exhausted()) -1L else buf.read(sink, byteCount)
        override fun close() = Unit
    }

    @Test
    fun store_roundtrips_and_keeps_its_files_under_the_given_directory() = runTest {
        FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
        val store = appleEngineCacheStore(dir)

        store.put("yt.recommended", "kJQP7kiw5Fk", emptyMap(), sourceOf("""{"items":[]}"""))

        val entry = assertNotNull(store.get("yt.recommended", "kJQP7kiw5Fk"))
        val out = Buffer()
        entry.open().use { src -> while (src.read(out, 4096L) != -1L) { /* to EOF */ } }
        assertEquals("""{"items":[]}""", out.readUtf8())
        assertEquals(listOf("kJQP7kiw5Fk"), store.list("yt.recommended").map { it.key })
        assertTrue(entry.meta.updatedAt > 0L, "timestamps come from the wall clock")
        assertTrue(FileSystem.SYSTEM.exists(dir / "engine-cache.db"), "index lands in the given directory")
    }

    @Test
    fun a_second_store_over_the_same_directory_sees_what_the_first_wrote() = runTest {
        FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
        appleEngineCacheStore(dir).put("yt.session", "params", mapOf("v" to "1"), sourceOf("cached"))

        val entry = assertNotNull(appleEngineCacheStore(dir).get("yt.session", "params"))
        assertEquals(mapOf("v" to "1"), entry.meta.custom)
    }

    @Test
    fun the_default_directory_is_under_application_support() {
        val path = defaultEngineCacheDirectory().toString()
        assertTrue(path.contains("Application Support"), path)
        assertTrue(path.endsWith("ymusic-engine-cache"), path)
    }
}
