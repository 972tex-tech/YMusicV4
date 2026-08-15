package io.ymusic.desktop.update

import com.sun.jna.Library
import com.sun.jna.Native
import java.io.File

/**
 * Replacing a macOS app bundle the way Sparkle does: assemble a COMPLETE bundle elsewhere, then
 * exchange it with the installed one in a single atomic operation.
 *
 * Two reasons it must be whole-bundle rather than patched in place:
 *  - a signed bundle seals every file it contains, so writing anything inside a signed .app breaks
 *    its signature (and eventually Gatekeeper);
 *  - an exchange has no instant where the install path is missing or half-written, which a
 *    rename-aside-then-rename-in sequence cannot avoid.
 *
 * The assembled bundle is byte-identical to the one we published and signed — unchanged files are
 * hardlinked from the running copy, and a hardlink shares content, which is what a signature
 * covers. Nothing is added inside it (progress markers live beside it) for the same reason.
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
