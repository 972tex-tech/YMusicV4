package ymusic.quickjs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * __hostData is the one native that hands JS-supplied (off, len) to the HOST as a raw pointer —
 * this module runs remotely-updatable JS, so an unchecked range is an out-of-bounds read of host
 * memory shipped straight into a pipe. Both shims must reject it like every other range-taking
 * native (__segTake, __utf8Decode, …).
 */
class HostDataBoundsTest {

    private suspend fun jsSendOutcome(js: String): String = run {
        val rt = QuickJsRuntime.create()
        try {
            val report = CompletableDeferred<String>()
            rt.onAccept("report") { p ->
                val b = Buffer()
                while (p.receive(b, 64L) != -1L) { /* to EOF */ }
                report.complete(b.readUtf8())
            }
            rt.onAccept("sink") { _ -> awaitCancellation() } // hold the pipe open, read nothing
            rt.load(
                """
                (async function () {
                  var out = ymix.open("report");
                  var pipe = ymix.open("sink");
                  var buf = new Uint8Array(16);
                  try {
                    var r = $js;
                    if (r && r.then) await r;
                    out.send(ymix.utf8Encode("accepted"));
                  } catch (e) {
                    out.send(ymix.utf8Encode("throw:" + e.name));
                  }
                  out.close();
                })()
                """.trimIndent(),
            )
            report.await()
        } finally {
            rt.close()
        }
    }

    @Test
    fun oversized_len_is_rejected() = runTest {
        assertEquals("throw:RangeError", jsSendOutcome("pipe.send(buf, 0, 999999)"))
    }

    @Test
    fun negative_offset_is_rejected() = runTest {
        assertEquals("throw:RangeError", jsSendOutcome("pipe.send(buf, -8, 8)"))
    }

    @Test
    fun in_bounds_send_still_accepted() = runTest {
        assertEquals("accepted", jsSendOutcome("pipe.send(buf, 8, 8)"))
    }
}
