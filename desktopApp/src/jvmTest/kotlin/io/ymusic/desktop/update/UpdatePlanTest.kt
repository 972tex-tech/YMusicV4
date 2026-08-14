package io.ymusic.desktop.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Content addressing is what makes an update small: the decision to fetch a file is made on its
 *  hash, never its version or its path. */
class UpdatePlanTest {

    private fun release(vararg files: Pair<String, String>) = Release(
        versionCode = 2,
        versionName = "1.0.1",
        url = "https://example.test/YMusic.dmg",
        sha256 = "deadbeef",
        sizeBytes = 72_000_000,
        notes = "",
        files = files.map { (path, hash) -> FileEntry(path, hash, 1_000) },
        blobBaseUrl = "https://example.test/blobs",
    )

    @Test
    fun only_files_whose_content_is_absent_are_downloaded() {
        val installed = mapOf(
            "runtime/lib/modules" to "aaa", // the ~68MB JVM, unchanged
            "app/commonUi.jar" to "old",
        )
        val plan = planUpdate(installed, release("runtime/lib/modules" to "aaa", "app/commonUi.jar" to "new"))

        assertEquals(listOf("app/commonUi.jar"), plan.toDownload.map { it.path })
        assertEquals(listOf("runtime/lib/modules"), plan.reusable.map { it.path })
        assertEquals(1_000L, plan.downloadBytes, "the unchanged runtime is not fetched")
        assertEquals(2_000L, plan.totalBytes)
    }

    @Test
    fun a_file_that_only_moved_is_reused_not_refetched() {
        // Same bytes under a new name — the jar hash in a filename changes every dependency bump,
        // so matching on path would refetch content already on disk.
        val plan = planUpdate(mapOf("app/lib-1.0.jar" to "same"), release("app/lib-2.0.jar" to "same"))

        assertTrue(plan.toDownload.isEmpty())
        assertEquals(listOf("app/lib-2.0.jar"), plan.reusable.map { it.path })
    }

    @Test
    fun files_no_longer_in_the_release_are_marked_obsolete() {
        val plan = planUpdate(
            mapOf("app/keep.jar" to "a", "app/dropped.jar" to "b"),
            release("app/keep.jar" to "a"),
        )
        assertEquals(listOf("app/dropped.jar"), plan.obsolete)
        assertTrue(!plan.isNoOp, "a removal is still a change to apply")
    }

    @Test
    fun reinstalling_the_same_content_is_a_no_op() {
        val plan = planUpdate(mapOf("app/a.jar" to "x"), release("app/a.jar" to "x"))
        assertTrue(plan.isNoOp)
        assertEquals(0L, plan.downloadBytes)
    }

    @Test
    fun a_fresh_install_downloads_everything() {
        val plan = planUpdate(emptyMap(), release("app/a.jar" to "x", "app/b.jar" to "y"))
        assertEquals(2, plan.toDownload.size)
        assertTrue(plan.reusable.isEmpty())
    }

    @Test
    fun a_v1_manifest_offers_no_file_level_route() {
        val v1 = Release(1, "1.0.0", "https://example.test/a.dmg", "abc", 1, "")
        assertTrue(!v1.supportsFileLevelUpdate, "without files the only route is the full installer")
    }

    @Test
    fun blob_urls_are_content_addressed() {
        val r = release("app/a.jar" to "9f2a")
        assertEquals("https://example.test/blobs/9f2a", r.blobUrl(r.files.first()))
    }
}
