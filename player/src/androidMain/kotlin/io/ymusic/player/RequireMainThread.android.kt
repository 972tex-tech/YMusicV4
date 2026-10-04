package io.ymusic.player

import android.os.Looper

internal actual fun requireMainThread() {
    check(Looper.myLooper() == Looper.getMainLooper()) { "Player is main-thread only" }
}
