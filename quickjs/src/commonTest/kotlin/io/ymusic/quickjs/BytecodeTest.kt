package io.ymusic.quickjs

import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `compile()` -> `create(bytecode)`: the real bundle runs from stripped bytecode. */
class BytecodeTest {

    private fun ByteArray.containsSub(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    @Test
    fun bundle_runs_from_bytecode() = runTest {
        val bc = QuickJsRuntime.compile(TEST_ENGINE_JS)
        val rt = QuickJsRuntime.create(bc)
        try {
            val pipe = rt.openPipe("test/rt/echo")
            val out = Buffer().writeUtf8("hello from bytecode 🎵")
            pipe.sink.send(out, out.size)
            pipe.sink.close()
            val all = Buffer()
            while (pipe.source.receive(all, 256L) != -1L) { /* to EOF */ }
            assertEquals("hello from bytecode 🎵", all.readUtf8())
        } finally {
            rt.close()
        }
    }

    @Test
    fun function_source_is_stripped_from_bytecode() = runTest {
        val bc = QuickJsRuntime.compile(TEST_ENGINE_JS)
        val fromBytecode = QuickJsRuntime.create(bc)
        try {
            val s = fromBytecode.load("String(__jsOpen)")!!
            assertTrue("[native code]" in s, "expected stripped placeholder, got: $s")
        } finally {
            fromBytecode.close()
        }
        // control: the same function created from source keeps its real text
        val fromSource = QuickJsRuntime.create()
        try {
            val s = fromSource.load("String(__jsOpen)")!!
            assertFalse("[native code]" in s, "source-created runtime unexpectedly stripped: $s")
        } finally {
            fromSource.close()
        }
    }

    @Test
    fun bytecode_blob_carries_no_source_text() = runTest {
        val bc = QuickJsRuntime.compile(
            """var keep = "LITERAL_ATOM_STAYS";
              |function f() { /* SOURCE_MARKER_MUST_NOT_SURVIVE */ return keep; }
            """.trimMargin(),
        )
        // control: data (string literals) still crosses into the blob as atoms...
        assertTrue(bc.containsSub("LITERAL_ATOM_STAYS".encodeToByteArray()))
        // ...but the function's source text (a comment can exist nowhere else) is gone
        assertFalse(bc.containsSub("SOURCE_MARKER_MUST_NOT_SURVIVE".encodeToByteArray()))
    }

    @Test
    fun versions_are_installed_before_the_bytecode_program() = runTest {
        val bc = QuickJsRuntime.compile(TEST_ENGINE_JS)
        val rt = QuickJsRuntime.create(bc, appVersion = 7)
        try {
            assertEquals("7", rt.load("String(versions.app)"))
            assertEquals("true", rt.load("Object.isFrozen(versions)"))
            // the global binding itself is non-writable: a bundle can't clobber it
            assertEquals("7", rt.load("versions = 123; String(versions.app)"))
        } finally {
            rt.close()
        }
    }

    @Test
    fun garbage_bytecode_fails_create() = runTest {
        assertFailsWith<QuickJsException> {
            QuickJsRuntime.create(byteArrayOf(0x7f, 0x01, 0x02, 0x03))
        }
    }

    @Test
    fun compile_error_surfaces_the_js_message() = runTest {
        val e = assertFailsWith<QuickJsException> { QuickJsRuntime.compile("function ] nope") }
        assertTrue(!e.message.isNullOrBlank())
    }
}
