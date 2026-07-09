package ymusic.quickjs

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The native JSON globals take a JS-owned typed array, then run user JS (property getters / a `off`
 * or `len` `valueOf`) that can detach or resize that buffer. A cached raw pointer then dangles into
 * freed memory — a UAF write in the encoder (audit J1), a UAF read in the parser (J2). The globals
 * are exposed to the untrusted remotely-updatable scraping JS this engine hosts, so a detach mid
 * call must fail cleanly, never corrupt memory. Under the native/ASan run these would abort on the
 * raw UAF; here they must throw a JS error instead.
 */
class JsonBufferSafetyTest {

    @Test
    fun encoder_throws_when_a_getter_detaches_the_destination_mid_fill() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val result = rt.load(
                """
                (function () {
                  var ab = new ArrayBuffer(16); // small: forces multiple produce()/memcpy rounds
                  var u8 = new Uint8Array(ab);
                  var evil = { get a() { ab.transfer(); return "detach-me-now-please"; } };
                  var enc = __jsonEncNew(evil);
                  try {
                    while (!__jsonEncDone(enc)) __jsonEncFill(enc, u8, 0);
                    return "no-throw";
                  } catch (e) { return "threw"; }
                })()
                """.trimIndent(),
            )
            assertEquals("threw", result, "the encoder must reject a detached destination, not write into freed memory")
        } finally {
            rt.close()
        }
    }

    @Test
    fun parser_throws_when_valueof_detaches_the_source() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val result = rt.load(
                """
                (function () {
                  var ab = new ArrayBuffer(8);
                  new Uint8Array(ab).set([0x7b, 0x7d]); // "{}"
                  var u8 = new Uint8Array(ab);
                  var p = __jsonNew(0);
                  var evilOff = { valueOf: function () { ab.transfer(); return 0; } };
                  try {
                    __jsonPush(p, u8, evilOff, 2);
                    return "no-throw";
                  } catch (e) { return "threw"; }
                })()
                """.trimIndent(),
            )
            assertEquals("threw", result, "the parser must reject a detached source, not read freed memory")
        } finally {
            rt.close()
        }
    }
}
