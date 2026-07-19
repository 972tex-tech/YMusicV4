package ymusic.player

/** Run a shell command; non-zero when it fails or the platform has no shell (iOS). */
internal expect fun runShell(command: String): Int
