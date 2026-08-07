package ymusic.player.webview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import ymusic.player.PlayableItem
import ymusic.player.PlaybackConfig
import ymusic.player.PlaybackState
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
