package ymusic.player

import platform.AVFoundation.AVPlayer

/**
 * Swift-facing helpers for the Apple `LocalPlayer`, so a native (SwiftUI) app can read state without
 * wrestling Kotlin's `StateFlow` generic. Exported through `Player.framework`. (`load` is a plain
 * function now, so Swift calls it directly.)
 */

/** The underlying `AVPlayer` — hand it to an `AVPlayerLayer` to display video. Null for non-Apple. */
fun LocalPlayer.avPlayer(): AVPlayer? = (this as? AppleLocalPlayer)?.player0

/** A plain snapshot of the current status (avoids exposing the `StateFlow` generic to Swift). */
fun LocalPlayer.currentStatus(): PlaybackStatus = status.value

/** A plain snapshot of the current play-head (position/duration/buffered). */
fun LocalPlayer.currentProgress(): Progress = progress.value
