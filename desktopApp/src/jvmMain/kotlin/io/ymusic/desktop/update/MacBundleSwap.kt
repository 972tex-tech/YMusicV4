package io.ymusic.desktop.update

import com.sun.jna.Library
import com.sun.jna.Native
import java.io.File

/**
 * Replacing a macOS app bundle the way Sparkle does: the new bundle is copied out whole beside the
 * installed one, then the two are exchanged in a single atomic rename — there is no instant where
 * the install path is missing or half-written. Nothing is ever written inside a bundle: a signed
 * .app seals every file it contains.
 */
object MacBundleSwap {

    private interface LibC : Library {
        /** macOS 10.12+. With [RENAME_SWAP] the two paths trade places atomically. */
        fun renamex_np(from: String, to: String, flags: Int): Int
    }

    private const val RENAME_SWAP = 0x00000002

    private val libc: LibC? by lazy {
        runCatching { Native.load("System", LibC::class.java) }.getOrNull()
    }

    val isSupported: Boolean
        get() = System.getProperty("os.name").lowercase().contains("mac") && libc != null

    /**
     * Exchange [a] and [b] atomically: afterwards [a] holds what was at [b] and vice versa, with no
     * observable moment in between. Both must exist and share a filesystem.
     */
    fun exchange(a: File, b: File) {
        val fs = libc ?: error("renamex_np unavailable")
        require(a.exists() && b.exists()) { "both paths must exist to exchange them" }
        val rc = fs.renamex_np(a.absolutePath, b.absolutePath, RENAME_SWAP)
        check(rc == 0) { "renamex_np failed (errno ${Native.getLastError()}) exchanging $a and $b" }
    }
}
