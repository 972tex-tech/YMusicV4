package io.ymusic.ytmedia.cache

import okio.Path

/** Free bytes on the volume holding [dir], or null when the platform can't tell. */
internal expect fun usableSpaceBytes(dir: Path): Long?
