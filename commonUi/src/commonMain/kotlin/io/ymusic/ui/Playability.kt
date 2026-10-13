package io.ymusic.ui

import io.ymusic.player.PlayerError

/** A YouTube playabilityStatus verdict on the video for this viewer: the user acts on it (sign in, pick
 *  another video), so it renders as its own localized message and is never a crash record. */
internal enum class Playability { SignInRequired, Unavailable }

internal fun PlayerError.playability(): Playability? = when (code) {
    "LOGIN_REQUIRED", "AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED" -> Playability.SignInRequired
    "UNPLAYABLE", "ERROR", "LIVE_STREAM_OFFLINE" -> Playability.Unavailable
    else -> null
}
