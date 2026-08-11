package io.ymusic.quickjs

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

    /**
     * A getter running mid-encode that reenters `__jsonEncFill` on the SAME encoder must be rejected:
     * the outer fill holds a raw frame pointer into the encoder's walk stack across the getter, and a
     * nested fill would realloc/pop that stack and dangle it (a UAF/double-free under ASan). The guard
     * throws instead; the outer encode then fails cleanly (not "no-throw", never a corrupt result).
     */
    @Test
    fun encoder_rejects_a_reentrant_fill_from_a_getter() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val result = rt.load(
                """
                (function () {
                  var u8 = new Uint8Array(new ArrayBuffer(8)); // small: forces multiple produce() rounds
                  var enc;
                  var evil = {
                    a: 1,
                    get b() { __jsonEncFill(enc, u8, 0); return 2; }, // reenter the same encoder
                  };
                  enc = __jsonEncNew(evil);
                  try {
                    while (!__jsonEncDone(enc)) __jsonEncFill(enc, u8, 0);
                    return "no-throw";
                  } catch (e) { return "threw"; }
                })()
                """.trimIndent(),
            )
            assertEquals("threw", result, "a reentrant __jsonEncFill on the same encoder must throw, not dangle the frame stack")
        } finally {
            rt.close()
        }
    }

    /**
     * The reentrancy guard must cover its OWN argument coercion: `off` can be an object whose valueOf
     * runs before the loop and reenters __jsonEncFill. The busy flag is armed before that coercion, so
     * the nested fill is rejected (and the throw propagates out of the outer coercion) rather than
     * running the encoder from a mutated walk position.
     */
    @Test
    fun encoder_rejects_reentry_from_an_off_valueof() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val result = rt.load(
                """
                (function () {
                  var u8 = new Uint8Array(new ArrayBuffer(8));
                  var enc = __jsonEncNew({ a: 1, b: 2 });
                  var evilOff = { valueOf: function () { __jsonEncFill(enc, u8, 0); return 0; } };
                  try {
                    __jsonEncFill(enc, u8, evilOff);
                    return "no-throw";
                  } catch (e) { return "threw"; }
                })()
                """.trimIndent(),
            )
            assertEquals("threw", result, "a reentrant fill via the off argument's valueOf must be rejected")
        } finally {
            rt.close()
        }
    }
}
