package ymusic.player

import java.awt.Container

/**
 * [videoContainer] is the shared video host the shell hands to the player (the same Swing panel the
 * JCEF WebView engine gets): the VLCJ engine adds its own video component into it and manages that
 * component's visibility. Null when local video is never shown (the playground drives the component
 * directly via [LocalPlayer.awtVideoComponent]).
 */
actual class LocalPlayerContext(val videoContainer: Container? = null)

actual fun createLocalPlayer(context: LocalPlayerContext): LocalPlayer = JvmLocalPlayer(context.videoContainer)
