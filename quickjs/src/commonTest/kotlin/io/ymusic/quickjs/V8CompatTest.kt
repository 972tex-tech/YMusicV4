package io.ymusic.quickjs

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V8-shaped natives: Function.prototype.toString, Error.stack, eval(TrustedScript). */
class V8CompatTest {

    @Test
    fun nativeFn_wraps_js_impl_as_c_function() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val s = rt.load(
                """
                (function () {
                  var f = __nativeFn("createElement", function (tag) { return String(tag); });
                  return Function.prototype.toString.call(f) + "|" + f("div");
                })()
                """.trimIndent(),
            )!!
            assertTrue(s.startsWith("function createElement() { [native code] }|div"), s)
        } finally {
            rt.close()
        }
    }

    @Test
    fun native_function_toString_is_v8_shaped() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val s = rt.load("Function.prototype.toString.call(parseInt)")!!
            assertEquals("function parseInt() { [native code] }", s)
            assertFalse("\n" in s)
        } finally {
            rt.close()
        }
    }

    @Test
    fun error_stack_is_v8_shaped_and_hides_eval() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val stack = rt.load(
                """
                (function () {
                  globalThis.location = { href: "https://example.test/watch" };
                  try { (0, eval)("throw new Error('x')"); }
                  catch (e) { return String(e.stack); }
                })()
                """.trimIndent(),
            )!!
            assertTrue(stack.startsWith("Error: x\n"), stack)
            assertFalse("eval" in stack, stack)
            assertFalse("anonymous" in stack, stack)
            assertFalse("Function" in stack, stack)
            assertTrue("https://example.test/watch" in stack, stack)
            assertFalse("youtube.com" in stack, stack)
            assertTrue(stack.count { it == '\n' } >= 3, stack)
        } finally {
            rt.close()
        }
    }

    @Test
    fun eval_runs_trusted_script_toString() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            assertEquals(
                "3",
                rt.load(
                    """
                    (function () {
                      function TS(s) { this._s = s; }
                      TS.prototype.toString = function () { return this._s; };
                      return String(eval(new TS('1+2')));
                    })()
                    """.trimIndent(),
                ),
            )
            assertEquals(
                "same",
                rt.load(
                    """
                    (function () {
                      var o = { x: 1 };
                      return eval(o) === o ? 'same' : 'evaled';
                    })()
                    """.trimIndent(),
                ),
            )
        } finally {
            rt.close()
        }
    }

    @Test
    fun time_zone_is_iana() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val z = rt.load("typeof __timeZone === 'function' ? __timeZone() : ''")!!
            assertTrue(z.contains('/') || z == "UTC" || z == "GMT", z)
        } finally {
            rt.close()
        }
    }
}
