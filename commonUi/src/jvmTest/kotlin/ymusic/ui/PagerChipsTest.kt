package ymusic.ui

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import ymusic.dynamicengine.yt.YtChip
import ymusic.dynamicengine.yt.YtFeedPage
import ymusic.dynamicengine.yt.YtSection
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun a_chip_click_keeps_the_row_it_came_from() = runTest {
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
        assertEquals(sortChips, filtered.chips, "the clicked row survives the reload")
        assertEquals("sort-oldest", filtered.selectedChipToken)
    }
}
