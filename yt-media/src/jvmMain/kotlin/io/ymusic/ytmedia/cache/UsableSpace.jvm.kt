package io.ymusic.ytmedia.cache

import okio.Path
import java.io.File

internal actual fun usableSpaceBytes(dir: Path): Long? = File(dir.toString()).usableSpace.takeIf { it > 0 }
