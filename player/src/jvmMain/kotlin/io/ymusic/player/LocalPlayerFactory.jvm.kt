package io.ymusic.player

/**
 * [videoSurface] is the off-screen frame endpoint the shell renders as a Compose `Image` (the twin of
 * the JCEF WebView's `JcefOsrSurface`): the VLCJ engine copies each decoded frame into it and owns its
 * visibility. When omitted, the factory creates one anyway so the picture is always reachable via
 * [LocalPlayer.localVideoSurface] (the playground drives its own surface that way).
 */
actual class LocalPlayerContext(val videoSurface: LocalVideoSurface? = null)

actual fun createLocalPlayer(context: LocalPlayerContext): LocalPlayer =
    JvmLocalPlayer(context.videoSurface ?: LocalVideoSurface())

/**
 * Whether libVLC can be found on this machine — the same search [createLocalPlayer] performs, so a
 * positive answer means a local file really will play rather than throwing `E60020`.
 *
 * Exposed here so the shell can *ask* without constructing a player: vlcj is this module's
 * dependency, and a first-run check has no business linking it a second time.
 */
fun isLibVlcAvailable(): Boolean =
    runCatching { uk.co.caprica.vlcj.factory.discovery.NativeDiscovery().discover() }.getOrDefault(false)
