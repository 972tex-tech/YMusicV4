package kmphttp.json

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonWriterTest {

    private suspend fun write(block: suspend JsonWriter.() -> Unit): String {
        val sink = BufferAsyncSink()
        val writer = JsonWriter(sink)
        writer.block()
        writer.flush()
        return sink.collected.readUtf8()
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
    fun drains_mid_document_when_the_buffer_exceeds_the_threshold() = runTest {
        val sink = BufferAsyncSink()
        val writer = JsonWriter(sink, flushThreshold = 8) // tiny → forces flushes mid-write
        writer.beginArray()
        repeat(50) { writer.value("item-$it") }
        writer.endArray()
        writer.flush()
        val arr = sink.collected.readUtf8().toJsonReader().readArray()
        assertEquals(50, arr.size)
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
