package io.ymusic.ui

import io.ymusic.dynamicengine.yt.YtFeedPage
import io.ymusic.dynamicengine.yt.YtSection
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Pager fetch watchdog: a wedged engine call (seen live on Android — CookieManager deadlocked
 * against WebView browser-process startup) must surface as the Failed page with Retry, never an
 * eternal spinner — and the retry path must work once the underlying call recovers.
 */
class PagerWatchdogTest {

    @Test
    fun hung_fetch_times_out_into_failed_and_retry_recovers() = runTest {
        var calls = 0
        val pager = Pager(backgroundScope, fetchTimeoutMs = 1_000) {
            calls++
            if (calls == 1) awaitCancellation() // wedged forever — only the watchdog ends it
            YtFeedPage(sections = listOf(YtSection(items = emptyList())))
        }

        pager.refresh()
        val failed = pager.state.first { it !is Pager.State.Loading }
        assertTrue(failed is Pager.State.Failed, "$failed")
        assertEquals(Pager.TIMEOUT_CODE, failed.code)

        // The user taps Retry; the fetch works this time.
        pager.refresh()
        val ready = pager.state.first { it !is Pager.State.Loading }
        assertTrue(ready is Pager.State.Ready, "$ready")
        assertEquals(2, calls)
    }
}
