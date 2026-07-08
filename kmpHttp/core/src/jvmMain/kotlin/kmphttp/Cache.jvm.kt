package kmphttp

import okio.Path

actual typealias Cache = okhttp3.Cache

actual fun DefaultPlatformCache(
    folderName: Path,
    maxSize: Long
): Cache = okhttp3.Cache(folderName.toFile(), maxSize)
