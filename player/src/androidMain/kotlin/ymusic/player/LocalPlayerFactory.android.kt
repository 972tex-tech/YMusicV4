package ymusic.player

import android.content.Context

actual class LocalPlayerContext(val androidContext: Context)

actual fun createLocalPlayer(context: LocalPlayerContext): LocalPlayer =
    AndroidLocalPlayer(context.androidContext.applicationContext)
