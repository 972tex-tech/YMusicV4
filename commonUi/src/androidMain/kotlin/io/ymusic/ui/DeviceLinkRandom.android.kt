package io.ymusic.ui

import java.security.SecureRandom

internal actual fun deviceLinkRandomBytes(n: Int): ByteArray =
    ByteArray(n).also { SecureRandom().nextBytes(it) }
