package io.ymusic.desktop.media

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises the whole JNA↔Objective-C bridge headlessly on a real macOS runtime: runtime class
 * creation, command registration, info-dict building, and the published values read back straight
 * from `MPNowPlayingInfoCenter`. Skipped off-macOS. Media-key delivery itself needs a live run —
 * this proves everything up to the OS boundary.
 */
class MacNowPlayingSessionTest {

    private object NoCommands : MediaSessionCommands {
        override fun togglePlay() = Unit

        override fun play() = Unit

        override fun pause() = Unit

        override fun next() = Unit

        override fun previous() = Unit

        override fun seekTo(positionMs: Long) = Unit
    }

    @Test
    fun publishes_and_reads_back_now_playing_info() {
        if ("mac" !in (System.getProperty("os.name")?.lowercase() ?: "")) return
        val session = MacNowPlayingSession(NoCommands)
        try {
            session.update(
                MediaSessionState(
                    title = "Ünïcode Song",
                    artist = "An artist",
                    playing = true,
                    positionMs = 42_500,
                    durationMs = 279_000,
                    speed = 1.0f,
                    hasItem = true,
                ),
            )
            assertEquals("Ünïcode Song", session.publishedValue("MPMediaItemPropertyTitle"))
            assertEquals("An artist", session.publishedValue("MPMediaItemPropertyArtist"))
            assertEquals(279.0, session.publishedValue("MPMediaItemPropertyPlaybackDuration"))
            assertEquals(42.5, session.publishedValue("MPNowPlayingInfoPropertyElapsedPlaybackTime"))
            assertEquals(1.0, session.publishedValue("MPNowPlayingInfoPropertyPlaybackRate"))
            assertEquals(1L, session.publishedPlaybackState(), "playing")

            session.update(
                MediaSessionState(
                    title = "Ünïcode Song", artist = null, playing = false,
                    positionMs = 50_000, durationMs = 279_000, speed = 1.0f, hasItem = true,
                ),
            )
            assertEquals(0.0, session.publishedValue("MPNowPlayingInfoPropertyPlaybackRate"), "paused = rate 0")
            assertEquals(2L, session.publishedPlaybackState(), "paused")

            session.update(
                MediaSessionState(
                    title = null, artist = null, playing = false,
                    positionMs = 0, durationMs = null, speed = 1.0f, hasItem = false,
                ),
            )
            assertEquals(null, session.publishedValue("MPMediaItemPropertyTitle"), "cleared on empty queue")
            assertEquals(3L, session.publishedPlaybackState(), "stopped")
        } finally {
            session.release()
        }
    }
}
