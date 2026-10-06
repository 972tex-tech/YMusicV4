package io.ymusic.ui

import io.ymusic.dynamicengine.yt.YtChip
import io.ymusic.dynamicengine.yt.YtFeedPage
import io.ymusic.dynamicengine.yt.YtSection
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Chip carry-over across refreshes: a chip CLICK keeps the row it came from (chip-filtered pages
 * often omit it), but a plain refresh is a new surface — a channel tab switch must not inherit the
 * previous tab's sort chips (live bug: the Playlists tab showed the Videos tab's Latest/Popular/
 * Oldest, and clicking one replayed a Videos sort token into the Playlists feed).
 */
class PagerChipsTest {

    private fun page(chips: List<YtChip> = emptyList()) =
        YtFeedPage(sections = listOf(YtSection(items = emptyList())), chips = chips)

    private suspend fun Pager.awaitReady(): Pager.State.Ready {
        val state = state.first { it !is Pager.State.Loading }
        assertTrue(state is Pager.State.Ready, "$state")
        return state
    }

    @Test
    fun a_plain_refresh_drops_the_previous_pages_chips() = runTest {
        val sortChips = listOf(YtChip("Latest", token = "sort-latest", selected = true))
        var chipless = false
        val pager = Pager(backgroundScope) { if (chipless) page() else page(sortChips) }

        pager.refresh() // the Videos tab: server-sent sort chips
        assertEquals(sortChips, pager.awaitReady().chips)

        chipless = true
        pager.refresh() // the Playlists tab: no chips of its own — none may leak in
        assertEquals(emptyList(), pager.awaitReady().chips)
    }

    @Test
    fun a_chip_click_keeps_the_row_it_came_from_and_moves_the_selection() = runTest {
        val sortChips = listOf(
            YtChip("Latest", token = "sort-latest", selected = true),
            YtChip("Oldest", token = "sort-oldest"),
        )
        var first = true
        val pager = Pager(backgroundScope) { continuation ->
            // The chip-filtered reload returns no chip row of its own.
            if (first) { first = false; page(sortChips) } else page()
        }

        pager.refresh()
        assertEquals(sortChips, pager.awaitReady().chips)

        pager.refresh(chipToken = "sort-oldest")
        val filtered = pager.awaitReady()
        assertEquals(
            listOf(
                YtChip("Latest", token = "sort-latest"),
                YtChip("Oldest", token = "sort-oldest", selected = true),
            ),
            filtered.chips,
            "the clicked row survives the reload with the selection moved to the clicked chip",
        )
        assertEquals("sort-oldest", filtered.selectedChipToken)
    }

    @Test
    fun a_fresh_rows_server_marked_selection_beats_the_stale_clicked_token() = runTest {
        // Live search shape: the chip-filtered response returns its OWN row with FRESH tokens —
        // the clicked token matches none of them — and marks the applied chip selected instead.
        val initial = listOf(
            YtChip("All", selected = true), // the selected search chip carries no endpoint at all
            YtChip("Videos", token = "videos-old"),
        )
        val filteredRow = listOf(
            YtChip("All", token = "all-fresh"),
            YtChip("Videos", token = "videos-fresh", selected = true),
        )
        var first = true
        val pager = Pager(backgroundScope) { continuation ->
            if (first) { first = false; page(initial) } else page(filteredRow)
        }

        pager.refresh()
        pager.awaitReady()

        pager.refresh(chipToken = "videos-old")
        val filtered = pager.awaitReady()
        assertEquals(filteredRow, filtered.chips)
        assertEquals("videos-fresh", filtered.selectedChipToken, "retry re-runs the FRESH token")
    }

    @Test
    fun a_failed_keep_content_reload_keeps_the_list_and_emits_an_error() = runTest {
        // Pull-to-refresh and chip taps both go through reload(): a failure must keep the current
        // data on screen (never a full-screen error page) and surface the error as an event the UI
        // shows as a snackbar.
        val chips = listOf(YtChip("All", token = "all", selected = true))
        var fail = false
        val pager = Pager(backgroundScope) {
            if (fail) throw RuntimeException("boom")
            page(chips)
        }

        pager.refresh()
        assertEquals(chips, pager.awaitReady().chips)

        // Subscribe to the error event BEFORE the failing reload — the flow has no replay, so a late
        // subscriber would miss it.
        val awaitError = async { pager.errors.first() }
        runCurrent()

        fail = true
        pager.reload(chipToken = "all")
        assertEquals("E20033", awaitError.await(), "the failure surfaced as an error event")

        val state = pager.state.value
        assertTrue(state is Pager.State.Ready, "kept the list, not an error page: $state")
        assertEquals(chips, state.chips, "old data survives a failed reload")
        assertFalse(state.refreshing, "the pull indicator stops")
    }
}
