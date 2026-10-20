package io.ymusic.ytmedia.cache

import okio.Path
import java.io.File

actual fun usableSpaceBytes(dir: Path): Long? = File(dir.toString()).takeIf { it.isDirectory }?.usableSpace
