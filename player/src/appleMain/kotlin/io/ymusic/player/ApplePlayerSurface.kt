package io.ymusic.player

/** Platform-native AVPlayerLayer host owned by an Apple [AppleLocalPlayer]. */
internal interface ApplePlayerSurface {
    fun setVisible(visible: Boolean)

    fun release()
}
