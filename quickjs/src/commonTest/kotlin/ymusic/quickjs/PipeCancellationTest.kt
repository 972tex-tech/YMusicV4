package ymusic.quickjs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Any abnormal exit from send/receive (cancellation included) closes that side of the pipe — ONE
 * predictable state, never a half-transferred stream. Without this rule, a send cancelled while
 * parked at the 64 KB cap left the peer a truncated stream with NO error signal (measured: 65536
 * of 204800 bytes, error=none), spliced the next message into the middle of it, and could hang the
 * next send forever.
 */
class PipeCancellationTest {

    @Test
    fun cancelled_mid_stream_send_resets_the_pipe() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val report = CompletableDeferred<String>()
            rt.onAccept("report") { p ->
                val b = Buffer()
                while (p.receive(b, 8192L) != -1L) { /* to EOF */ }
                report.complete(b.readUtf8())
            }
            rt.load(
                """
                ymix.onAccept("sink", async (pipe) => {
                  await new Promise((res) => { globalThis.__go = res; }); // hold reads until poked
                  var buf = new Uint8Array(8192), total = 0, err = "none";
                  try {
                    for (;;) {
                      var r = pipe.receive(buf, 0, buf.length);
                      var n = typeof r === "number" ? r : await r;
                      if (n < 0) break;
                      total += n;
                    }
                  } catch (e) { err = e.message; }
                  var out = ymix.open("report");
                  out.send(ymix.utf8Encode(total + "|" + err));
                  out.close();
                });
                """.trimIndent(),
            )
            val pipe = rt.openPipe("sink")
            val payload = Buffer()
            repeat(200 * 1024) { payload.writeByte(it and 0xFF) }
            val sender = launch { pipe.send(payload, payload.size) }
            withContext(Dispatchers.Default) { delay(300) } // real time: park at the 64 KB cap
            sender.cancelAndJoin()

            // The abort moved bytes -> the pipe RESET: the peer's read throws the reason (its
            // truncated buffered prefix is discarded, not served as a fake clean stream)...
            rt.load("__go()")
            val (bytes, err) = report.await().split("|", limit = 2)
            assertEquals("0", bytes)
            assertTrue(err.startsWith("send aborted mid-stream:"), "peer saw: $err")

            // ...and this side has declared itself done writing — no silent splicing.
            assertFailsWith<IllegalStateException> { pipe.send(Buffer().writeUtf8("SENTINEL"), 8L) }
        } finally {
            rt.close()
        }
    }

    @Test
    fun cancelled_parked_receive_closes_the_read_side() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            rt.load("""ymix.onAccept("later", (pipe) => { globalThis.__later = pipe; });""")
            val pipe = rt.openPipe("later")
            val parked = launch { pipe.receive(Buffer(), 16L) }
            withContext(Dispatchers.Default) { delay(200) } // real time: let it park
            parked.cancelAndJoin()

            // The abort closed our read end: this side's next receive is a misuse...
            assertFailsWith<IllegalStateException> { pipe.receive(Buffer(), 16L) }
            // ...and the peer's next send fails with EPIPE instead of writing into the void.
            val report = CompletableDeferred<String>()
            rt.onAccept("report") { p ->
                val b = Buffer()
                while (p.receive(b, 64L) != -1L) { /* to EOF */ }
                report.complete(b.readUtf8())
            }
            rt.load(
                """
                (function () {
                  var out = ymix.open("report");
                  var p;
                  try { p = __later.send(ymix.utf8Encode("anyone?")); } catch (e) { p = Promise.reject(e); }
                  Promise.resolve(p).then(
                    function () { out.send(ymix.utf8Encode("accepted")); out.close(); },
                    function (e) { out.send(ymix.utf8Encode(e.message)); out.close(); });
                })()
                """.trimIndent(),
            )
            assertEquals("EPIPE: broken pipe", report.await())
        } finally {
            rt.close()
        }
    }
}
