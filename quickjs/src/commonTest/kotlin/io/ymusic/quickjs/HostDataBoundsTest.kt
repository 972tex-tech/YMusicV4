package io.ymusic.quickjs

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * __hostData is the one native that hands JS-supplied (off, len) to the HOST as a raw pointer —
 * this module runs remotely-updatable JS, so an unchecked range is an out-of-bounds read of host
 * memory shipped straight into a pipe. Both shims must reject it like every other range-taking
 * native (__pbRecv, __utf8Decode, …). Driven via the `test/send-bounds` fixture.
 */
class HostDataBoundsTest {

    private suspend fun jsSendOutcome(off: Int, len: Int): String = run {
        val rt = QuickJsRuntime.create()
        try {
            rt.onAccept("sink-hold") { _ -> awaitCancellation() } // hold the pipe open, read nothing
            val pipe = rt.openPipe("test/send-bounds")
            val head = Buffer().writeUtf8("""{"off":$off,"len":$len}""")
            pipe.sink.send(head, head.size)
            pipe.sink.close()
            val out = Buffer()
            while (pipe.source.receive(out, 64L) != -1L) { /* to EOF */ }
            pipe.source.close()
            out.readUtf8()
        } finally {
            rt.close()
        }
    }

    @Test
    fun oversized_len_is_rejected() = runTest {
        assertEquals("throw:RangeError", jsSendOutcome(0, 999999))
    }

    @Test
    fun negative_offset_is_rejected() = runTest {
        assertEquals("throw:RangeError", jsSendOutcome(-8, 8))
    }

    @Test
    fun in_bounds_send_still_accepted() = runTest {
        assertEquals("accepted", jsSendOutcome(8, 8))
    }
}
