package ymusic.player

/**
 * The native-local [MediaEngine]: plays exactly ONE local media file (a [PlayableItem.LocalUri])
 * natively — load, then play / pause / resume / seek / stop, reporting position and state. No queue,
 * no gapless, no playlist; those belong to a higher layer that drives one engine per item.
 *
 * Backends: Android Media3 ExoPlayer, Apple AVPlayer (iOS + macOS), JVM libVLC (VLCJ). Built via
 * [createLocalPlayer]. Main-thread only — see [MediaEngine] for the full contract.
 *
 * (Local-only capabilities — an equalizer/`audioSessionId` handle, embedded-caption and audio-track
 * selection — will be added as capability interfaces this type implements, not on [MediaEngine].)
 */
interface LocalPlayer :
    MediaEngine<PlayableItem.LocalUri>,
    VideoQualitySelectable,
    CaptionSelectable,
    AudioTrackSelectable
