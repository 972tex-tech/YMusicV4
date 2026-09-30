package io.ymusic.ui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
internal actual fun deviceLinkRandomBytes(n: Int): ByteArray {
    val bytes = ByteArray(n)
    val status = bytes.usePinned { pinned ->
        SecRandomCopyBytes(kSecRandomDefault, n.convert(), pinned.addressOf(0))
    }
    check(status == errSecSuccess) { "SecRandomCopyBytes $status" }
    return bytes
}
