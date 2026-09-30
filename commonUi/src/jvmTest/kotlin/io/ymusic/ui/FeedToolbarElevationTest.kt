package io.ymusic.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.dp
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.embeddedEngineJsSource
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.dynamicengine.yt.YtFeedPage
import io.ymusic.dynamicengine.yt.YtSection
import io.ymusic.dynamicengine.yt.YtSectionHeader
import kotlinx.coroutines.CoroutineScope
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Discover's app bar lifts from the feed's TrackScroll. A chipless page still used to emit an empty
 * chip row (0-height) at index 0; LazyColumn then treated the next item as first-visible, so the
 * bar cast a shadow on a page that was still at the top — including after a tab swipe from a
 * neighbour that was also at the top.
 */
@OptIn(ExperimentalTestApi::class)
class FeedToolbarElevationTest {

    @Test
    fun a_chipless_feed_at_the_top_does_not_lift_the_bar() {
        val host = ScrollHost()
        runComposeUiTest {
            setContent {
                val scope = rememberCoroutineScope()
                CompositionLocalProvider(LocalScrollHost provides host) {
                    YMusicTheme(dark = false) {
                        PhoneFeed { FeedList(chiplessPager(scope, "A"), coordinator(scope)) }
                    }
                }
            }
            waitUntilAtLeastOneExists(hasText("A 0"), 5_000)
            waitForIdle()
            assertFalse(host.scrolled, "chipless feed at the top must stay flat")
        }
    }

    @Test
    fun scrolling_a_chipless_feed_lifts_the_bar() {
        val host = ScrollHost()
        runComposeUiTest {
            setContent {
                val scope = rememberCoroutineScope()
                CompositionLocalProvider(LocalScrollHost provides host) {
                    YMusicTheme(dark = false) {
                        PhoneFeed { FeedList(chiplessPager(scope, "A"), coordinator(scope)) }
                    }
                }
            }
            waitUntilAtLeastOneExists(hasText("A 0"), 5_000)
            waitForIdle()
            onNode(hasScrollAction()).performScrollToIndex(12)
            waitUntil(timeoutMillis = 5_000L) { host.scrolled }
            assertTrue(host.scrolled)
        }
    }

    @Test
    fun swiping_to_another_chipless_tab_at_the_top_does_not_lift_the_bar() {
        val host = ScrollHost()
        val tab = mutableIntStateOf(0)
        runComposeUiTest {
            setContent {
                val scope = rememberCoroutineScope()
                val home = remember { chiplessPager(scope, "Home") }
                val music = remember { chiplessPager(scope, "Music") }
                var selected by tab
                CompositionLocalProvider(LocalScrollHost provides host) {
                    YMusicTheme(dark = false) {
                        PhoneFeed {
                            SwipeTabSwitcher(selected, listOf(0, 1), onSelect = { selected = it }) { current ->
                                FeedList(if (current == 0) home else music, coordinator(scope))
                            }
                        }
                    }
                }
            }
            waitUntilAtLeastOneExists(hasText("Home 0"), 5_000)
            waitForIdle()
            assertFalse(host.scrolled, "home at the top")

            // Same state change SwipeTabSwitcher makes when a swipe commits.
            runOnIdle { tab.intValue = 1 }
            waitUntilAtLeastOneExists(hasText("Music 0"), 5_000)
            waitForIdle()
            assertFalse(host.scrolled, "incoming chipless tab at the top must stay flat")
        }
    }
}

/** Phone-portrait Discover: square list, the layout the tab swipe actually runs on. */
@Composable
private fun PhoneFeed(content: @Composable () -> Unit) {
    Box(Modifier.size(360.dp, 640.dp), content = { content() })
}

private fun coordinator(scope: CoroutineScope) = PlaybackCoordinator(
    AppGraph(
        engines = DynamicEngineManager(
            CannedHttp { request, _ -> textResponse(request, "{}", "application/json") },
            appVersion = 1,
            jsSource = embeddedEngineJsSource(),
        ),
        player = StubPlayer(),
        playerSurface = {},
    ),
    scope,
)

private fun chiplessPager(scope: CoroutineScope, prefix: String): Pager {
    val items = List(24) { i ->
        YtFeedItem(
            kind = YtFeedItem.KIND_VIDEO,
            id = "$prefix-$i",
            title = "$prefix $i",
            subtitleLine1 = "Channel",
        )
    }
    val page = YtFeedPage(
        sections = listOf(YtSection(header = YtSectionHeader(title = prefix), items = items)),
    )
    return Pager(scope) { page }.also { it.refresh() }
}
