package io.ymusic.ytmedia.cache

import okio.Path

/** Free bytes on the volume holding the existing [dir] (0 = full), or null when the platform can't tell. */
expect fun usableSpaceBytes(dir: Path): Long?
