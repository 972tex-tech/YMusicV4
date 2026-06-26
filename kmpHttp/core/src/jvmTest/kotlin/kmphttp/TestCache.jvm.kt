package kmphttp

import okio.Path.Companion.toPath
import java.nio.file.Files

actual fun newTestCache(): Cache =
    DefaultPlatformCache(
        Files.createTempDirectory("kmphttp-cache").toString().toPath(),
        10L * 1024 * 1024,
    )
