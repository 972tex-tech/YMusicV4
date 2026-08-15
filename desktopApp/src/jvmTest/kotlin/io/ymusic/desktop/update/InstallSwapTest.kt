package io.ymusic.desktop.update

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The swap is the one step that can leave a user with no working app, so its failure modes matter
 *  more than its happy path. */
class InstallSwapTest {

    private val tmp = File("build/test-swap-${System.nanoTime()}").apply { mkdirs() }

    @AfterTest fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun tree(name: String, vararg files: Pair<String, String>): File =
        File(tmp, name).apply {
            mkdirs()
            files.forEach { (path, content) ->
                File(this, path).apply { parentFile.mkdirs() }.writeText(content)
            }
        }

    @Test
    fun the_swap_replaces_the_install_and_removes_the_old_copy() {
        val install = tree("YMusic.app", "Contents/app/a.jar" to "v1", "Contents/gone.txt" to "old")
        val staged = tree("staged-2", "Contents/app/a.jar" to "v2")

        swapInstall(install, staged)

        assertEquals("v2", File(install, "Contents/app/a.jar").readText())
        assertTrue(!File(install, "Contents/gone.txt").exists(), "a deleted file is gone after the swap")
        assertTrue(!staged.exists(), "the staged tree moved rather than copied")
        assertEquals(1, tmp.listFiles().orEmpty().size, "no backup left behind")
    }

    @Test
    fun a_missing_staged_tree_is_refused_before_anything_is_touched() {
        val install = tree("YMusic.app", "Contents/app/a.jar" to "v1")
        assertFailsWith<IllegalArgumentException> { swapInstall(install, File(tmp, "not-there")) }
        assertEquals("v1", File(install, "Contents/app/a.jar").readText(), "the install is untouched")
    }

    @Test
    fun hashing_walks_the_whole_tree_relative_to_its_root() {
        val root = tree("app", "a.txt" to "hello", "sub/b.txt" to "hello")
        val hashes = hashInstalledFiles(root)

        assertEquals(setOf("a.txt", "sub/b.txt"), hashes.keys)
        assertEquals(hashes["a.txt"], hashes["sub/b.txt"], "identical content hashes identically")
        assertEquals(64, hashes.getValue("a.txt").length)
    }

    @Test
    fun planning_against_a_real_tree_finds_only_the_changed_file() {
        val root = tree("app", "keep.jar" to "same", "change.jar" to "before")
        val installed = hashInstalledFiles(root)
        val release = Release(
            versionCode = 2,
            versionName = "1.0.1",
            url = "https://e.test/i.dmg",
            sha256 = "aa",
            sizeBytes = 1,
            notes = "",
            files = listOf(
                FileEntry("keep.jar", installed.getValue("keep.jar"), 4),
                FileEntry("change.jar", "ffff", 5),
            ),
            blobBaseUrl = "https://e.test/blobs",
        )
        val plan = planUpdate(installed, release)

        assertEquals(listOf("change.jar"), plan.toDownload.map { it.path })
        assertEquals(listOf("keep.jar"), plan.reusable.map { it.path })
    }
}
