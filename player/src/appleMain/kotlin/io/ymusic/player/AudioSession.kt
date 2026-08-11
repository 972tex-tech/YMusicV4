package io.ymusic.player

/**
 * Activate the playback AVAudioSession before the first AVPlayer item prepares — required for a
 * music app on iOS (audio focus, background audio with the `audio` background mode). macOS has no
 * AVAudioSession; no-op there.
 */
internal expect fun ensurePlaybackAudioSession()
