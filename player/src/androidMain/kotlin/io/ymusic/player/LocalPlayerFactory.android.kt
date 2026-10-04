package io.ymusic.player

import android.content.Context
import android.view.ViewGroup

/**
 * [videoContainer] is the shared video host the shell hands to the player: the engine adds its own
 * `SurfaceView` into it and manages that view's visibility.
 * Null on hosts that never show local video (audio-only shells, tests).
 */
actual class LocalPlayerContext(val androidContext: Context, val videoContainer: ViewGroup? = null)

actual fun createLocalPlayer(context: LocalPlayerContext): LocalPlayer =
    AndroidLocalPlayer(context.androidContext.applicationContext, context.videoContainer)
