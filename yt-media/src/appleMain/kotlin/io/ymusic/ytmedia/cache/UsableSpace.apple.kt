package io.ymusic.ytmedia.cache

import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber

@OptIn(ExperimentalForeignApi::class)
actual fun usableSpaceBytes(dir: Path): Long? {
    val attrs = NSFileManager.defaultManager.attributesOfFileSystemForPath(dir.toString(), error = null)
    return (attrs?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue
}
