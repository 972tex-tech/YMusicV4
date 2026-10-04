package io.ymusic.player

import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities

internal actual fun requireMainThread() {
    if (GraphicsEnvironment.isHeadless()) return
    check(SwingUtilities.isEventDispatchThread()) { "Player is main-thread only" }
}
