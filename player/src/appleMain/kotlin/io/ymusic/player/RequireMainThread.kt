package io.ymusic.player

import platform.Foundation.NSThread

internal actual fun requireMainThread() {
    check(NSThread.isMainThread) { "Player is main-thread only" }
}
