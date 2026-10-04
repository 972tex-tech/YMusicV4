package io.ymusic.player

import platform.AVFoundation.AVPlayer

/**
 * Swift-facing helpers so a native (SwiftUI) app can drive `:player` through `Player.framework`
 * without wrestling Kotlin generics/DSLs. Covers the AVFoundation [LocalPlayer].
 */

/** The underlying `AVPlayer` — hand it to an `AVPlayerLayer` to display video. Null for non-Apple. */
fun LocalPlayer.avPlayer(): AVPlayer? = (this as? AppleLocalPlayer)?.player0

/** A plain snapshot of the current status (avoids exposing the `StateFlow` generic to Swift). */
fun MediaEngine<*>.currentStatus(): PlaybackStatus = status.value

/** A plain snapshot of the current play-head (position/duration/buffered). */
fun MediaEngine<*>.currentProgress(): Progress = progress.value

/**
 * Swift-friendly [PlaybackConfig] factory — Swift can't ergonomically build the `Pair<String, Long>`
 * preference fields, so it passes the three common knobs and audio-only maps to
 * audio-only is [PlaybackConfig.audioOnly], not a quality token.
 */
fun playbackConfig(volume: Float = 1f, speed: Float = 1f, audioOnly: Boolean = false): PlaybackConfig =
    PlaybackConfig(
        audioOnly = audioOnly,
        volume = volume,
        speed = speed,
    )
