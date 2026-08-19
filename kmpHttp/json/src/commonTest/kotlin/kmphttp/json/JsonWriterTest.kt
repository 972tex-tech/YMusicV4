package kmphttp.json

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonWriterTest {

    private fun write(block: JsonWriter.() -> Unit): String {
        val writer = JsonWriter.Sync()
        writer.block()
        return writer.buffer.readUtf8()
    }

    @Test
    fun flat_object() = runTest {
        val out = write {
            beginObject()
            name("s").value("hi")
            name("n").value(42L)
            name("b").value(true)
            name("z").value(null as String?)
            endObject()
        }
        assertEquals("""{"s":"hi","n":42,"b":true,"z":null}""", out)
    }

    @Test
    fun nested_objects_and_arrays() = runTest {
        val out = write {
            beginObject()
            name("items").beginArray()
            beginObject()
            name("id").value("a")
            endObject()
            value("x")
            endArray()
            name("empty").beginArray().endArray()
            endObject()
        }
        assertEquals("""{"items":[{"id":"a"},"x"],"empty":[]}""", out)
    }

    @Test
    fun escapes_control_backslash_quote_and_passes_astral_raw() = runTest {
        // Inputs carry a real quote/backslash/newline/tab, a real control char (U+0001), and a real
        // astral char (😀 = U+1F600). The writer escapes only the JSON-mandatory chars and emits the
        // emoji as raw UTF-8; the expected literal spells out each resulting escape sequence.
        val out = write {
            beginArray()
            value("quote\" back\\ new\nline\ttab")
            value("ctrlend")
            value("emoji😀")
            endArray()
        }
        val expected = "[" +
            "\"quote\\\" back\\\\ new\\nline\\ttab\"," +
            "\"ctrl\\u0001end\"," +
            "\"emoji😀\"" +
            "]"
        assertEquals(expected, out)
    }

    @Test
    fun round_trips_through_the_reader() = runTest {
        val out = write {
            beginObject()
            name("kind").value("video")
            name("live").value(true)
            name("tags").beginArray().value("a").value("b").endArray()
            endObject()
        }
        val obj = out.toJsonReader().readObject()
        assertEquals("video", obj["kind"]!!.jsonPrimitive.content)
        assertEquals(true, obj["live"]!!.jsonPrimitive.boolean)
        assertEquals(2, obj["tags"]!!.jsonArray.size)
    }

    @Test
    fun the_caller_drains_through_its_own_flush() = runTest {
        val collected = Buffer()
        val writer = JsonWriter.Async { w -> collected.writeAll(w.buffer) }
        writer.beginArray()
        repeat(50) {
            writer.value("item-$it")
            // Draining mid-document is the caller's call now, and safe at any point: the writer
            // keeps its shape state separately from the bytes.
            if (it % 10 == 0) writer.flush()
        }
        writer.endArray()
        writer.flush()

        assertEquals(50, collected.readUtf8().toJsonReader().readArray().size)
    }

    @Test
    fun a_sync_writer_drains_without_a_coroutine() {
        val collected = Buffer()
        val writer = JsonWriter.Sync { w -> collected.writeAll(w.buffer) }
        writer.beginObject().name("n").value(42L).endObject()
        writer.flush()

        assertEquals("""{"n":42}""", collected.readUtf8())
    }

    @Test
    fun rejects_a_value_without_a_name_in_an_object() = runTest {
        assertFailsWith<IllegalStateException> {
            write {
                beginObject()
                value("no name first")
            }
        }
    }
}
