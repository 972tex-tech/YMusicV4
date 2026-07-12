package ymusic.player

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueueControllerTest {
    private fun items(n: Int) = (0 until n).map { PlayableItem.LocalUri("file:///$it.mp3") }

    private fun controller(n: Int, playIndex: Int = 0, seed: Int = 1) =
        QueueController(Random(seed)).apply { setQueue(items(n), playIndex) }

    @Test
    fun linear_advance_and_exhaustion() {
        val q = controller(3)
        assertEquals(1, q.advance(userInitiated = false))
        assertEquals(2, q.advance(userInitiated = false))
        assertNull(q.advance(userInitiated = false))
        assertEquals(2, q.state.currentIndex, "exhausted advance must not move")
    }

    @Test
    fun loop_all_wraps_both_directions() {
        val q = controller(3, playIndex = 2)
        q.setLoopMode(LoopMode.All)
        assertEquals(0, q.advance(userInitiated = false))
        assertEquals(2, q.previous(), "previous from 0 wraps under All")
    }

    @Test
    fun loop_one_repeats_on_ended_but_not_on_user_skip() {
        val q = controller(3, playIndex = 1)
        q.setLoopMode(LoopMode.One)
        assertEquals(1, q.advance(userInitiated = false))
        assertEquals(2, q.advance(userInitiated = true))
    }

    @Test
    fun shuffle_plays_every_item_once_before_stopping() {
        val q = controller(5)
        q.setShuffle(true)
        val seen = mutableSetOf(q.state.currentIndex)
        repeat(4) { seen += q.advance(userInitiated = false) ?: error("exhausted too early") }
        assertEquals((0..4).toSet(), seen, "shuffle must cover the whole queue")
        assertNull(q.advance(userInitiated = false), "no loop: exhausted after full coverage")
    }

    @Test
    fun shuffle_with_loop_all_restarts_without_immediate_repeat() {
        val q = controller(4)
        q.setShuffle(true)
        q.setLoopMode(LoopMode.All)
        repeat(3) { q.advance(userInitiated = false) }
        val last = q.state.currentIndex
        val next = q.advance(userInitiated = false)
        assertTrue(next != null && next != last, "cycle restart must avoid an immediate repeat")
    }

    @Test
    fun shuffle_previous_walks_played_history() {
        val q = controller(5)
        q.setShuffle(true)
        val first = q.state.currentIndex
        val second = q.advance(userInitiated = false)!!
        val third = q.advance(userInitiated = false)!!
        assertEquals(second, q.previous())
        assertEquals(first, q.previous())
        assertNull(q.previous(), "history exhausted")
        // Forward again re-records history from here.
        val again = q.advance(userInitiated = false)!!
        assertEquals(first, q.previous(), "prev returns to where we branched, not $again")
    }

    @Test
    fun add_before_current_shifts_current() {
        val q = controller(3, playIndex = 1)
        q.add(listOf(PlayableItem.YtVideoId("a"), PlayableItem.YtVideoId("b")), at = 0)
        assertEquals(3, q.state.currentIndex)
        assertEquals(5, q.state.items.size)
    }

    @Test
    fun add_next_inserts_after_current() {
        val q = controller(3, playIndex = 1)
        val next = PlayableItem.YtVideoId("abc")
        q.addNext(listOf(next))
        assertEquals(next, q.state.items[2])
        assertEquals(1, q.state.currentIndex)
        assertEquals(2, q.advance(userInitiated = false), "inserted item plays next")
    }

    @Test
    fun remove_current_keeps_slot_and_reports() {
        val q = controller(3, playIndex = 1)
        assertTrue(q.removeAt(1))
        assertEquals(1, q.state.currentIndex, "slot is kept — the following item takes it")
        assertEquals(2, q.state.items.size)
    }

    @Test
    fun remove_last_current_clamps() {
        val q = controller(2, playIndex = 1)
        assertTrue(q.removeAt(1))
        assertEquals(0, q.state.currentIndex)
    }

    @Test
    fun remove_before_current_shifts_current_down() {
        val q = controller(3, playIndex = 2)
        assertFalse(q.removeAt(0))
        assertEquals(1, q.state.currentIndex)
    }

    @Test
    fun remove_to_empty() {
        val q = controller(1)
        assertTrue(q.removeAt(0))
        assertEquals(-1, q.state.currentIndex)
        assertNull(q.state.currentItem)
    }

    @Test
    fun move_tracks_current_item() {
        val q = controller(4, playIndex = 1)
        val current = q.state.currentItem
        q.move(1, 3)
        assertEquals(3, q.state.currentIndex)
        assertEquals(current, q.state.currentItem)
        q.move(0, 3)
        assertEquals(2, q.state.currentIndex)
        assertEquals(current, q.state.currentItem)
    }

    @Test
    fun set_queue_clamps_play_index() {
        val q = QueueController(Random(1))
        q.setQueue(items(2), playIndex = 9)
        assertEquals(1, q.state.currentIndex)
        q.setQueue(emptyList(), playIndex = 0)
        assertEquals(-1, q.state.currentIndex)
    }

    @Test
    fun move_to_bounds_checked() {
        val q = controller(2)
        assertFalse(q.moveTo(5))
        assertTrue(q.moveTo(1))
        assertEquals(1, q.state.currentIndex)
    }
}
