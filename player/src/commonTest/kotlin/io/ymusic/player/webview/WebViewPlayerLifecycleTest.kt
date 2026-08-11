package io.ymusic.player.webview

import io.ymusic.player.PlayableItem
import io.ymusic.player.PlaybackConfig
import io.ymusic.player.PlaybackState
import io.ymusic.player.VideoQualityState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WebViewPlayerLifecycleTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialized_page_is_reused_for_subsequent_video_loads() = runTest(dispatcher) {
        lateinit var bridge: FakeBridge
        val player = WebViewPlayerImpl(
            "window.testBundle=true",
            1,
            { "<html><head><script>\nINJECT_JS_HERE\n</script></head><body></body></html>" },
        ) { sink ->
            FakeBridge(sink).also { bridge = it }
        }

        player.load(PlayableItem.YtMedia("first"), config = PlaybackConfig())
        advanceUntilIdle()
        assertEquals(1, bridge.documentLoads)
        assertIs<PlaybackState.Uninitialized>(player.status.value.state)

        bridge.post("connected", "{}")
        advanceUntilIdle()
        assertTrue(bridge.evaluations.last().contains("first"))
        bridge.post(
            "state_changed",
            """{"newState":"ready","playWhenReady":true}""",
        )
        assertIs<PlaybackState.Prepared>(player.status.value.state)

        val evaluationsBefore = bridge.evaluations.size
        player.load(PlayableItem.YtMedia("second"), config = PlaybackConfig())
        advanceUntilIdle()

        assertEquals(1, bridge.documentLoads, "ordinary media loads must not navigate the initialized page")
        assertEquals(evaluationsBefore + 1, bridge.evaluations.size)
        assertTrue(bridge.evaluations.last().contains("second"))
        player.release()
    }

    @Test
    fun the_quality_selection_is_always_forwarded_to_the_page() = runTest(dispatcher) {
        lateinit var bridge: FakeBridge
        val player = WebViewPlayerImpl(
            "window.testBundle=true",
            1,
            { "<html><head><script>\nINJECT_JS_HERE\n</script></head><body></body></html>" },
        ) { sink ->
            FakeBridge(sink).also { bridge = it }
        }
        player.load(PlayableItem.YtMedia("first"), config = PlaybackConfig())
        advanceUntilIdle()
        bridge.post("connected", "{}")
        advanceUntilIdle()

        // The user re-asserts audio-only after the engine's recorded config drifted from actual
        // playback: same quality string as the load's config. Host-side dedupe swallowing this was
        // the "nothing happens" bug — the page's own qualitySetting diff owns the dedupe, so the
        // engine must forward every selection.
        val before = bridge.evaluations.size
        player.setConfig(PlaybackConfig(preferVideoQuality = VideoQualityState.AUDIO_ONLY to 1L))
        advanceUntilIdle()
        assertEquals(before + 1, bridge.evaluations.size, "a re-asserted quality must reach the page")
        assertTrue(bridge.evaluations.last().contains(VideoQualityState.AUDIO_ONLY))

        // Several changed fields still ride ONE ymusicAction round trip (a BULK), like legacy.
        player.setConfig(
            PlaybackConfig(
                preferVideoQuality = VideoQualityState.AUDIO_ONLY to 2L,
                volume = 0.5f,
                speed = 1.5f,
                captionVisible = true,
            ),
        )
        advanceUntilIdle()
        assertEquals(before + 2, bridge.evaluations.size, "a multi-field config change must batch into one bulk")
        player.release()
    }

    private class FakeBridge(private val sink: WebViewMessageSink) : WebViewBridge {
        override val platform = "test"
        override val userAgent = "test-agent"
        var documentLoads = 0
        val evaluations = mutableListOf<String>()
        val visibility = mutableListOf<Boolean>()

        override fun load(html: String, baseUrl: String) {
            documentLoads++
        }

        override fun setVisible(visible: Boolean) {
            visibility += visible
        }

        override fun evaluate(js: String) {
            evaluations += js
        }

        override fun release() = Unit

        fun post(channel: String, payload: String) {
            sink.onMessage(channel, payload)
        }
    }
}
