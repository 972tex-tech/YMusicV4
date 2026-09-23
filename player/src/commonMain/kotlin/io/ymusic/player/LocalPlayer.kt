package io.ymusic.player

/**
 * The native-local [MediaEngine]: plays a local file (a [PlayableItem.LocalMedia]) natively — load,
 * then play / pause / resume / seek / stop, reporting position and state. The facade owns the queue;
 * [preload] may cue one next file on backends that can (Android ExoPlayer).
 *
 * Backends: Android Media3 ExoPlayer, Apple AVPlayer (iOS + macOS), JVM in-process libav + Java Sound. Built via
 * [createLocalPlayer]. Main-thread only — see [MediaEngine] for the full contract.
 *
 * (Local-only capabilities — an equalizer/`audioSessionId` handle, embedded-caption and audio-track
 * selection — will be added as capability interfaces this type implements, not on [MediaEngine].)
 */
interface LocalPlayer :
    MediaEngine<PlayableItem.LocalMedia>,
    VideoQualitySelectable,
    CaptionSelectable,
    AudioTrackSelectable {
    /** Extra caption tracks (YouTube timedtext for a downloaded file, etc.). Default ignores. */
    fun injectCaptionTracks(tracks: List<InjectedCaptionTrack>) {}
}
