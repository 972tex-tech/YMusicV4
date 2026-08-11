package io.ymusic.imageloader

/** okio's FileSystem.SYSTEM — not reachable from common code (absent on JS), so bridged here. */
internal expect val systemFileSystem: okio.FileSystem
