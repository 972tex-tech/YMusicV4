package io.ymusic.quickjs

/** Thrown when JS evaluation fails or a host callback rejects. */
class QuickJsException(message: String) : RuntimeException(message)
