package io.ymusic.player

/** Run a shell command; non-zero when it fails or the platform has no shell (iOS). */
internal expect fun runShell(command: String): Int

/** AVFoundation cannot open the media pipeline in a bare simctl-spawned iOS-simulator process
 *  (AVFoundation -11800 / CoreMedia -12746, on every simulator generation) — real-playback tests
 *  only run where playback actually works; app-context simulator playback is covered by the
 *  ios-player-lab spike. */
internal expect val canPlayRealMedia: Boolean
