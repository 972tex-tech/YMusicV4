package kmphttp

import kmphttp.cache.SystemFileSystem
import okio.Path.Companion.toPath
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
actual fun newTestCache(): Cache {
    val dir = ".".toPath() / "test-cache" / Uuid.random().toString()
    SystemFileSystem.mkdirs(dir)
    return DefaultPlatformCache(dir, 10L * 1024 * 1024)
}
