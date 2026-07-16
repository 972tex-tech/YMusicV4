package kmphttp.json

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonReaderTest {

    /** readObject stops at the root value's end: trailing NON-JSON bytes (e.g. the binary framing
     *  that follows a protocol head) stay in the source, byte-exact — even bytes that look like
     *  JSON whitespace. */
    @Test
    fun read_object_leaves_trailing_bytes_in_the_source() = runTest {
        val tail = byteArrayOf(0x20, 0x0A, 0x00, 0x00, -1, -1, -1, -1, 0x7B)
        val reader = ("""{"a":1}""".encodeToByteArray() + tail).toJsonReader()
        assertEquals(1, reader.readObject()["a"]!!.jsonPrimitive.int)
        assertContentEquals(tail, reader.source.readFully().readByteArray())
    }

    // ==================== Step-by-step API tests ====================

    @Test
    fun testReadSimpleObject() = runTest {
        val json = """{"name": "John", "age": 30}"""
        val reader = json.toJsonReader()

        reader.beginObject()
        assertTrue(reader.hasNextObjectKey())
        assertEquals("name", reader.nextKey())
        assertEquals(JsonTokenType.STRING, reader.peekNextTokenType())
        assertEquals("John", reader.nextValue().jsonPrimitive.content)

        assertTrue(reader.hasNextObjectKey())
        assertEquals("age", reader.nextKey())
        assertEquals(JsonTokenType.UNQUOTED_LITERAL, reader.peekNextTokenType())
        assertEquals(30, reader.nextValue().jsonPrimitive.int)

        assertFalse(reader.hasNextObjectKey())
        reader.endObject()
    }

    @Test
    fun testReadNestedObject() = runTest {
        val json = """{"person": {"name": "John"}, "active": true}"""
        val reader = json.toJsonReader()

        reader.beginObject()
        assertEquals("person", reader.nextKey())
        assertEquals(JsonTokenType.OBJECT, reader.peekNextTokenType())

        reader.beginObject()
        assertEquals("name", reader.nextKey())
        assertEquals("John", reader.nextValue().jsonPrimitive.content)
        reader.endObject()

        assertEquals("active", reader.nextKey())
        assertEquals(JsonTokenType.UNQUOTED_LITERAL, reader.peekNextTokenType())
        assertEquals(true, reader.nextValue().jsonPrimitive.boolean)
        reader.endObject()
    }

    @Test
    fun testReadArray() = runTest {
        val json = """[1, 2, 3]"""
        val reader = json.toJsonReader()

        reader.beginArray()
        assertTrue(reader.hasNextArrayElement())
        assertEquals(1, reader.nextValue().jsonPrimitive.int)
        assertTrue(reader.hasNextArrayElement())
        assertEquals(2, reader.nextValue().jsonPrimitive.int)
        assertTrue(reader.hasNextArrayElement())
        assertEquals(3, reader.nextValue().jsonPrimitive.int)
        assertFalse(reader.hasNextArrayElement())
        reader.endArray()
    }

    @Test
    fun testSkipValue() = runTest {
        val json = """{"skip": {"nested": [1,2,3]}, "keep": "value"}"""
        val reader = json.toJsonReader()

        reader.beginObject()
        assertEquals("skip", reader.nextKey())
        reader.skipValue() // skip complex nested structure

        assertEquals("keep", reader.nextKey())
        assertEquals("value", reader.nextValue().jsonPrimitive.content)
        reader.endObject()
    }

    @Test
    fun testReadEscapedString() = runTest {
        val json = """{"text": "Hello\nWorld\t!"}"""
        val reader = json.toJsonReader()

        reader.beginObject()
        assertEquals("text", reader.nextKey())
        assertEquals("Hello\nWorld\t!", reader.nextValue().jsonPrimitive.content)
        reader.endObject()
    }

    @Test
    fun testReadUnicodeEscape() = runTest {
        val json = """{"emoji": "\u0048\u0065\u006C\u006C\u006F"}"""
        val reader = json.toJsonReader()

        reader.beginObject()
        assertEquals("emoji", reader.nextKey())
        assertEquals("Hello", reader.nextValue().jsonPrimitive.content)
        reader.endObject()
    }

    @Test
    fun testReadSurrogatePairEscapes() = runTest {
        // Astral characters escape as surrogate PAIRS; each half written alone becomes '?'.
        val json = "{\"emoji\": \"\\uD83D\\uDE00 ok \\uD83C\\uDF89\"}"
        val reader = json.toJsonReader()

        reader.beginObject()
        assertEquals("emoji", reader.nextKey())
        assertEquals("\uD83D\uDE00 ok \uD83C\uDF89", reader.nextValue().jsonPrimitive.content)
        reader.endObject()
    }

    @Test
    fun testReadSurrogatePairEscapesAcrossChunks() = runTest {
        // One byte per read: the pair straddles every possible refill boundary, exercising the
        // needs-more-bytes resume path between the high and low halves.
        val json = "{\"emoji\": \"x\\uD83D\\uDE00y\"}"
        val reader = json.toJsonReaderChunked(1)

        reader.beginObject()
        assertEquals("emoji", reader.nextKey())
        assertEquals("x\uD83D\uDE00y", reader.nextValue().jsonPrimitive.content)
        reader.endObject()
    }

    @Test
    fun testLoneSurrogateEscapeDoesNotCorruptWhatFollows() = runTest {
        val json = "{\"s\": \"\\uD83Dab\"}"
        val reader = json.toJsonReader()

        reader.beginObject()
        assertEquals("s", reader.nextKey())
        assertEquals("?ab", reader.nextValue().jsonPrimitive.content)
        reader.endObject()
    }

    @Test
    fun testReadUtf8Unicode() = runTest {
        val json = """{"emoji": "👍🎉", "chinese": "你好世界", "mixed": "Hello 世界 🌍"}"""
        val reader = json.toJsonReader()

        reader.beginObject()
        assertEquals("emoji", reader.nextKey())
        assertEquals("👍🎉", reader.nextValue().jsonPrimitive.content)
        assertEquals("chinese", reader.nextKey())
        assertEquals("你好世界", reader.nextValue().jsonPrimitive.content)
        assertEquals("mixed", reader.nextKey())
        assertEquals("Hello 世界 🌍", reader.nextValue().jsonPrimitive.content)
        reader.endObject()
    }

    @Test
    fun testReadNull() = runTest {
        val json = """{"value": null}"""
        val reader = json.toJsonReader()

        reader.beginObject()
        assertEquals("value", reader.nextKey())
        assertEquals(JsonTokenType.UNQUOTED_LITERAL, reader.peekNextTokenType())
        assertTrue(reader.nextValue() is kotlinx.serialization.json.JsonNull)
        reader.endObject()
    }

    @Test
    fun testSkipRemainingObject() = runTest {
        val json = """{"a": 1, "b": 2, "c": 3}"""
        val reader = json.toJsonReader()

        reader.beginObject()
        assertEquals("a", reader.nextKey())
        assertEquals(1, reader.nextValue().jsonPrimitive.int)
        // skip remaining keys
        while (reader.hasNextObjectKey()) { reader.nextKey(); reader.skipValue() }
        reader.endObject()
    }

    @Test
    fun testSkipRemainingArray() = runTest {
        val json = """[1, 2, 3, 4, 5]"""
        val reader = json.toJsonReader()

        reader.beginArray()
        assertEquals(1, reader.nextValue().jsonPrimitive.int)
        // skip remaining elements
        while (reader.hasNextArrayElement()) reader.skipValue()
        reader.endArray()
    }

    @Test
    fun testNextValueReadsFullObject() = runTest {
        val json = """{"nested": {"a": 1, "b": [2, 3]}}"""
        val reader = json.toJsonReader()

        reader.beginObject()
        assertEquals("nested", reader.nextKey())
        val nested = reader.nextValue() as JsonObject
        assertEquals(1, nested["a"]?.jsonPrimitive?.int)
        reader.endObject()
    }

    // ==================== Malformed JSON tests ====================

    @Test
    fun testMalformedMultipleCommasInObject() = runTest {
        val json = """{"a": 1,, "b": 2}"""
        val reader = json.toJsonReader()

        reader.beginObject()
        assertTrue(reader.hasNextObjectKey())
        assertEquals("a", reader.nextKey())
        assertEquals(1, reader.nextValue().jsonPrimitive.int)

        try {
            reader.hasNextObjectKey() // should throw on double comma
            kotlin.test.fail("Expected JsonSyntaxException")
        } catch (e: JsonSyntaxException) {
            assertEquals(e.message?.contains("Expected '\"'"), true)
        }
    }

    @Test
    fun testMalformedMultipleCommasInArray() = runTest {
        val json = """[1,, 2]"""
        val reader = json.toJsonReader()

        reader.beginArray()
        assertTrue(reader.hasNextArrayElement())
        assertEquals(1, reader.nextValue().jsonPrimitive.int)

        try {
            reader.hasNextArrayElement() // should throw on double comma
            kotlin.test.fail("Expected JsonSyntaxException")
        } catch (e: JsonSyntaxException) {
            assertEquals(e.message?.contains("Expected"), true)
        }
    }

    @Test
    fun testMalformedTrailingCommaInObject() = runTest {
        val json = """{"a": 1,}"""
        val reader = json.toJsonReader()

        reader.beginObject()
        assertEquals("a", reader.nextKey())
        assertEquals(1, reader.nextValue().jsonPrimitive.int)

        try {
            reader.hasNextObjectKey() // should throw: comma then }
            kotlin.test.fail("Expected JsonSyntaxException")
        } catch (e: JsonSyntaxException) {
            assertEquals(e.message?.contains("Expected '\"'"), true)
        }
    }

    // ==================== readObject() / readArray() state machine API tests ====================

    @Test
    fun testReadObjectSimple() = runTest {
        val json = """{"name": "John", "age": 30}"""
        val obj = json.toJsonReader().readObject()

        assertEquals("John", obj["name"]?.jsonPrimitive?.content)
        assertEquals(30, obj["age"]?.jsonPrimitive?.int)
    }

    @Test
    fun testReadObjectNested() = runTest {
        val json = """{"person": {"name": "John"}, "active": true}"""
        val obj = json.toJsonReader().readObject()

        val person = obj["person"] as JsonObject
        assertEquals("John", person["name"]?.jsonPrimitive?.content)
        assertEquals(true, obj["active"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun testReadObjectWithArray() = runTest {
        val json = """{"items": [1, 2, 3]}"""
        val obj = json.toJsonReader().readObject()

        val items = obj["items"]?.jsonArray
        assertEquals(3, items?.size)
        assertEquals(1, items?.get(0)?.jsonPrimitive?.int)
        assertEquals(2, items?.get(1)?.jsonPrimitive?.int)
        assertEquals(3, items?.get(2)?.jsonPrimitive?.int)
    }

    @Test
    fun testReadObjectEscapedString() = runTest {
        val json = """{"text": "Hello\nWorld\t!"}"""
        val obj = json.toJsonReader().readObject()

        assertEquals("Hello\nWorld\t!", obj["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun testReadObjectUnicodeEscape() = runTest {
        val json = """{"emoji": "\u0048\u0065\u006C\u006C\u006F"}"""
        val obj = json.toJsonReader().readObject()

        assertEquals("Hello", obj["emoji"]?.jsonPrimitive?.content)
    }

    @Test
    fun testReadObjectUtf8Unicode() = runTest {
        val json = """{"emoji": "👍🎉", "chinese": "你好世界", "mixed": "Hello 世界 🌍"}"""
        val obj = json.toJsonReader().readObject()

        assertEquals("👍🎉", obj["emoji"]?.jsonPrimitive?.content)
        assertEquals("你好世界", obj["chinese"]?.jsonPrimitive?.content)
        assertEquals("Hello 世界 🌍", obj["mixed"]?.jsonPrimitive?.content)
    }

    @Test
    fun testReadObjectNull() = runTest {
        val json = """{"value": null}"""
        val obj = json.toJsonReader().readObject()

        assertTrue(obj["value"] is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun testReadObjectDeeplyNested() = runTest {
        val json = """{"a": {"b": {"c": {"d": "deep"}}}}"""
        val obj = json.toJsonReader().readObject()

        val a = obj["a"] as JsonObject
        val b = a["b"] as JsonObject
        val c = b["c"] as JsonObject
        assertEquals("deep", c["d"]?.jsonPrimitive?.content)
    }

    @Test
    fun testReadObjectMixedArray() = runTest {
        val json = """{"data": [1, "two", true, null, {"nested": "obj"}]}"""
        val obj = json.toJsonReader().readObject()

        val data = obj["data"]?.jsonArray!!
        assertEquals(5, data.size)
        assertEquals(1, data[0].jsonPrimitive.int)
        assertEquals("two", data[1].jsonPrimitive.content)
        assertEquals(true, data[2].jsonPrimitive.boolean)
        assertTrue(data[3] is kotlinx.serialization.json.JsonNull)
        assertEquals("obj", (data[4] as JsonObject)["nested"]?.jsonPrimitive?.content)
    }

    // ==================== skipValue() — the pull-API way to skip unknown keys ====================

    @Test
    fun skipValue_skips_unknown_scalars_objects_and_arrays() = runTest {
        // Unknown keys of every value kind, including a trailing one (skipping the last key must
        // still leave the object closable with '}', not ',').
        val json = """{"id": 1, "obj": {"a": 1, "b": 2}, "arr": [1, [2], {"c": 3}], "name": "test", "z": "last"}"""
        val reader = json.toJsonReader()
        var id = 0
        var name = ""
        reader.beginObject()
        while (reader.hasNextObjectKey()) {
            when (reader.nextKey()) {
                "id" -> id = reader.nextValue().jsonPrimitive.int
                "name" -> name = reader.nextValue().jsonPrimitive.content
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        reader.expectEof()

        assertEquals(1, id)
        assertEquals("test", name)
    }
}
