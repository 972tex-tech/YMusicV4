package projectcommon.log

import kmpcommon.log.Crumb
import kmpcommon.log.CrumbLogger
import kmpcommon.log.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrailMarkTest {

    private class Probe(crumbs: List<Crumb> = emptyList()) : CrumbLogger(crumbs) {
        override fun fork(crumbs: List<Crumb>): CrumbLogger = Probe(crumbs)

        override fun log(level: Logger.Level, message: String, error: Throwable?) = Unit
    }

    @Test
    fun deeper_cause_chain_mark_beats_the_wrapper_stamp() {
        val deep = Probe().fork("a").fork("b", mapOf("id" to 1))
        val inner = Exception("boom").markTrail(deep)
        val wrapped = RuntimeException("wrap", inner).markTrail(Probe().fork("outer"))
        assertEquals(listOf("a", "b"), wrapped.trailMark()?.map { it.tag })
        assertEquals(mapOf<String, Any>("id" to 1), wrapped.trailMark()?.last()?.prop)
    }

    @Test
    fun first_stamp_wins_on_one_throwable() {
        val e = Exception("x").markTrail(Probe().fork("deep"))
        e.markTrail(Probe().fork("shallow"))
        assertEquals(listOf("deep"), e.trailMark()?.map { it.tag })
    }

    @Test
    fun unmarked_throwable_has_no_trail() {
        assertNull(Exception("x").trailMark())
    }

    @Test
    fun null_logger_stamps_nothing() {
        assertNull(Exception("x").markTrail(null).trailMark())
    }
}
