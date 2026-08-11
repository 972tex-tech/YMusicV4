package io.ymusic.player

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.system

@OptIn(ExperimentalForeignApi::class)
internal actual fun runShell(command: String): Int = system(command)

internal actual val canPlayRealMedia: Boolean = true
