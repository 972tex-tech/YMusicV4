package ymusic.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MediaControlsTest {
    private class RecordingControls : MediaControls {
        val updates = mutableListOf<NowPlayingInfo?>()

        override fun update(info: NowPlayingInfo?) {
            updates += info
        }

        override fun release() {}
    }

    private class ScriptedPlayer : Player {
        val statusFlow = MutableStateFlow(PlaybackStatus())
        val progressFlow = MutableStateFlow(Progress())
        val queueFlow = MutableStateFlow(QueueState())
        val commands = mutableListOf<String>()

        override val status: StateFlow<PlaybackStatus> = statusFlow.asStateFlow()
        override val progress: StateFlow<Progress> = progressFlow.asStateFlow()
        override val queue: StateFlow<QueueState> = queueFlow.asStateFlow()
        override val videoQuality = MutableStateFlow(VideoQualityState.DEFAULT)
        override val captions = MutableStateFlow(CaptionState.DEFAULT)
        override val audioTracks = MutableStateFlow(AudioTrackState.DEFAULT)
        override val currentEngine = MutableStateFlow<MediaEngine<*>?>(null)
        override val sleepTimerMs = MutableStateFlow<Long?>(null)

        override fun setQueue(items: List<PlayableItem>, playIndex: Int, startPositionMs: Long, playWhenReady: Boolean) {}

        override fun add(items: List<PlayableItem>, at: Int?) {}

        override fun addToPlayNext(items: List<PlayableItem>) {}

        override fun move(from: Int, to: Int) {}

        override fun removeAt(index: Int) {}

        override fun clearQueue() {}

        override fun playAt(index: Int) {}

        override fun play() {
            commands += "play"
        }

        override fun pause() {
            commands += "pause"
        }

        override fun togglePlay() {
            commands += "toggle"
        }

        override fun stop() {
            commands += "stop"
        }

        override fun seekTo(positionMs: Long) {
            commands += "seek:$positionMs"
        }

        override fun skipNext() {
            commands += "next"
        }

        override fun skipPrev() {
            commands += "prev"
        }

        override fun fastForward(ms: Long) {}

        override fun rewind(ms: Long) {}

        override fun setLoopMode(mode: LoopMode) {}

        override fun setShuffle(enabled: Boolean) {}

        override fun setVolume(volume: Float) {}

        override fun setSpeed(speed: Float) {}

        override fun setAudioOnly(audioOnly: Boolean) {}

        override fun selectVideoQuality(quality: String) {}

        override fun selectCaption(languageCode: String?) {}

        override fun setCaptionVisible(visible: Boolean) {}

        override fun selectAudioTrack(id: String) {}

        override fun setSleepTimer(durationMs: Long?) {}

        override fun release() {}
    }

    @Test
    fun derives_now_playing_from_the_flows() = runTest(StandardTestDispatcher()) {
        val player = ScriptedPlayer()
        val controls = RecordingControls()
        val job = bindMediaControls(player, controls, this) {
            NowPlayingDescription("Song ${(it as PlayableItem.LocalUri).uri}", artist = "Artist")
        }
        runCurrent()
        assertNull(controls.updates.last(), "idle: nothing published")

        player.queueFlow.value = QueueState(listOf(PlayableItem.LocalUri("a"), PlayableItem.LocalUri("b")), 0)
        player.statusFlow.value = PlaybackStatus(state = PlaybackState.Ready, playWhenReady = true)
        player.progressFlow.value = Progress(positionMs = 1000, durationMs = 60_000)
        runCurrent()

        val info = assertNotNull(controls.updates.last())
        assertEquals("Song a", info.title)
        assertEquals("Artist", info.artist)
        assertTrue(info.isPlaying)
        assertEquals(60_000, info.durationMs)
        assertTrue(info.hasNext)
        assertFalse(info.hasPrevious)

        player.statusFlow.value = PlaybackStatus(state = PlaybackState.Ready, playWhenReady = false)
        runCurrent()
        assertFalse(assertNotNull(controls.updates.last()).isPlaying)

        job.cancel()
    }

    @Test
    fun transport_commands_route_into_the_player() {
        val player = ScriptedPlayer()
        player.handle(TransportCommand.Play)
        player.handle(TransportCommand.Pause)
        player.handle(TransportCommand.Next)
        player.handle(TransportCommand.Previous)
        player.handle(TransportCommand.SeekTo(1234))
        player.handle(TransportCommand.Stop)
        assertEquals(listOf("play", "pause", "next", "prev", "seek:1234", "stop"), player.commands)
    }
}
