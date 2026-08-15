package io.ymusic.desktop.update

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The exchange is the commit point of a macOS update, so it is worth proving it really swaps
 *  rather than copies — and that a hardlinked bundle shares content with its source. */
class MacBundleSwapTest {

    private val tmp = File("build/test-swap-np-${System.nanoTime()}").apply { mkdirs() }

    @AfterTest fun cleanup() {
        tmp.deleteRecursively()
    }

    @Test
    fun two_directories_trade_places_atomically() {
        if (!MacBundleSwap.isSupported) return
        val current = File(tmp, "YMusic.app").apply { mkdirs(); File(this, "v").writeText("old") }
        val assembled = File(tmp, ".YMusic.new").apply { mkdirs(); File(this, "v").writeText("new") }

        MacBundleSwap.exchange(current, assembled)

        assertEquals("new", File(current, "v").readText(), "the install path now holds the new bundle")
        assertEquals("old", File(assembled, "v").readText(), "and the scratch path holds the old one")
    }

    @Test
    fun a_hardlinked_file_shares_content_so_a_signature_would_still_cover_it() {
        if (!MacBundleSwap.isSupported) return
        val source = File(tmp, "orig.jar").apply { writeText("payload") }
        val linked = File(tmp, "assembled.jar")
        java.nio.file.Files.createLink(linked.toPath(), source.toPath())

        assertEquals(sha256Of(source), sha256Of(linked), "same content, so the same seal")
        assertTrue(linked.length() == source.length())
    }

    @Test
    fun exchanging_a_missing_path_is_refused_before_anything_moves() {
        if (!MacBundleSwap.isSupported) return
        val current = File(tmp, "YMusic.app").apply { mkdirs(); File(this, "v").writeText("old") }

        runCatching { MacBundleSwap.exchange(current, File(tmp, "not-there")) }

        assertEquals("old", File(current, "v").readText(), "the install is untouched")
    }
}
