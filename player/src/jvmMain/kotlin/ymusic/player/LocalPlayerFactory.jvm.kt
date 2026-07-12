package ymusic.player

actual class LocalPlayerContext

actual fun createLocalPlayer(context: LocalPlayerContext): LocalPlayer = JvmLocalPlayer()
