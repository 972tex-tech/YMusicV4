package io.ymusic.quickjs

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Native atob/btoa/TextEncoder/TextDecoder (C, latin-1/UTF-8, no JS loops). */
class WebApiTest {

    @Test
    fun btoa_atob_roundtrip_latin1_and_urlsafe() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val s = rt.load(
                """
                (function () {
                  var a = btoa("hello");
                  var b = atob(a);
                  var bin = "";
                  for (var i = 0; i < 256; i++) bin += String.fromCharCode(i);
                  var r = atob(btoa(bin));
                  var same = r.length === 256;
                  for (var j = 0; j < 256; j++) if (r.charCodeAt(j) !== j) same = false;
                  var url = atob("aGVsbG8_"); /* url-safe ? -> / would be aGVsbG8/ invalid; use -_ */
                  url = atob("aGVsbG8=");
                  var us = atob("aGVsbG8");
                  var native = Function.prototype.toString.call(btoa) === "function btoa() { [native code] }"
                    && Function.prototype.toString.call(atob) === "function atob() { [native code] }";
                  return a + "|" + b + "|" + same + "|" + us + "|" + native;
                })()
                """.trimIndent(),
            )!!
            assertEquals("aGVsbG8=|hello|true|hello|true", s)
        } finally {
            rt.close()
        }
    }

    @Test
    fun btoa_rejects_code_units_above_255() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val s = rt.load(
                """
                (function () {
                  try { btoa("☺"); return "no-throw"; }
                  catch (e) { return e.name; }
                })()
                """.trimIndent(),
            )!!
            assertEquals("TypeError", s)
        } finally {
            rt.close()
        }
    }

    @Test
    fun textencoder_textdecoder_utf8_and_native_tostring() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val s = rt.load(
                """
                (function () {
                  var e = new TextEncoder();
                  var u = e.encode("café");
                  var hex = Array.from(u).map(function (b) { return b.toString(16); }).join(",");
                  var d = new TextDecoder().decode(u);
                  var empty = e.encode().length;
                  var enc = e.encoding;
                  var nt = Function.prototype.toString.call(TextEncoder) === "function TextEncoder() { [native code] }"
                    && Function.prototype.toString.call(e.encode) === "function encode() { [native code] }";
                  var into = new Uint8Array(16);
                  var r = e.encodeInto("ab", into);
                  return hex + "|" + d + "|" + empty + "|" + enc + "|" + nt + "|" + r.read + "|" + r.written + "|" + into[0] + "," + into[1];
                })()
                """.trimIndent(),
            )!!
            assertEquals("63,61,66,c3,a9|café|0|utf-8|true|2|2|97,98", s)
        } finally {
            rt.close()
        }
    }
}
