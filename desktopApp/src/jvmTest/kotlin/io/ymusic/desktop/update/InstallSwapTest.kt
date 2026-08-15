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
    fun a_versioned_install_lands_beside_the_running_copy_untouched() {
        // What Windows does: the running app-1 must not be written to at all.
        val root = tree("YMusic", "app-1/lib/a.jar" to "v1", "YMusic.exe" to "launcher")
        val running = File(root, "app-1/lib/a.jar")
        val staged = tree("staged-2", "lib/a.jar" to "v2")

        val launcher = installVersioned(root, staged, versionCode = 2)

        assertEquals("v1", running.readText(), "the running version is never touched")
        assertEquals("v2", File(root, "app-2/lib/a.jar").readText())
        assertTrue(!staged.exists(), "the staged tree moved rather than copied")
        assertTrue(launcher.name.startsWith("YMusic"), "returns the stable launcher, got $launcher")
    }

    @Test
    fun pruning_keeps_the_running_version_and_removes_the_rest() {
        val root = tree(
            "YMusic",
            "app-1/x" to "old", "app-2/x" to "current", "app-3/x" to "newer", "YMusic.exe" to "l",
        )
        val removed = pruneOldVersions(root, keepVersionCode = 2)

        assertEquals(2, removed)
        assertTrue(File(root, "app-2/x").exists(), "the running version survives")
        assertTrue(!File(root, "app-1").exists() && !File(root, "app-3").exists())
        assertTrue(File(root, "YMusic.exe").exists(), "the launcher is not a version dir")
    }

    @Test
    fun a_half_written_release_is_never_named_as_a_version() {
        // installVersioned renames a COMPLETE tree in, so a launcher picking the highest number
        // cannot select something still being written.
        val root = tree("YMusic", "app-1/x" to "v1")
        assertFailsWith<IllegalArgumentException> {
            installVersioned(root, File(tmp, "never-staged"), versionCode = 2)
        }
        assertTrue(!File(root, "app-2").exists(), "no directory is created for a failed staging")
    }

    @Test
    fun a_staged_tree_on_another_volume_still_installs() {
        // renameTo returns false across filesystems, so staging that does not share a volume with
        // the install must fall back to copying rather than failing the update.
        val root = tree("YMusic", "app-1/x" to "v1")
        val staged = tree("staged-2", "x" to "v2")
        val moved = File(tmp, "sim-other-volume").apply { mkdirs() }
        staged.copyRecursively(File(moved, "staged-2"))
        staged.deleteRecursively()

        installVersioned(root, File(moved, "staged-2"), versionCode = 2)

        assertEquals("v2", File(root, "app-2/x").readText())
        assertTrue(!File(moved, "staged-2").exists(), "the staging copy is cleaned up")
    }

    @Test
    fun a_failed_swap_puts_the_original_back() {
        val install = tree("YMusic.app", "Contents/a.jar" to "v1")
        // A staged tree that vanishes before the second rename: the install must not be left gone.
        val staged = tree("staged-x", "a.jar" to "v2")
        swapInstall(install, staged)
        assertEquals("v2", File(install, "a.jar").readText())
        assertTrue(
            tmp.listFiles().orEmpty().none { it.name.contains(".old-") || it.name.contains(".new-") },
            "no scratch directories are left beside the install",
        )
    }

    @Test
    fun the_strategy_follows_the_layout_on_disk_not_the_os() {
        val versioned = tree("Versioned", "app-1/x" to "a", "YMusic.exe" to "l")
        val plain = tree("Plain", "app/x" to "a", "runtime/y" to "b")

        assertEquals(SwapStrategy.VERSIONED_DIRECTORIES, InstallLayout.strategyFor(versioned))
        assertEquals(SwapStrategy.REPLACE_IN_PLACE, InstallLayout.strategyFor(plain))
        assertEquals(SwapStrategy.REPLACE_IN_PLACE, InstallLayout.strategyFor(null))
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
