package kmphttp.json.benchmark

import kmpcommon.io.AsyncSource
import kmpcommon.io.BufferedAsyncSource
import kmphttp.json.JsonReader
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Setup
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import okio.Buffer

/**
 * Benchmark comparing kotlinx-serialization vs JsonReader performance.
 *
 * Test scenarios:
 * - smallJson: simple object with 3 fields
 * - largeKeyValueJson: object with very large key and value strings
 * - largeKeyValueWithEscapeJson: same but with escape sequences
 * - manyKeysJson: object with 200 keys
 * - nestedDeeplyJson: deeply nested object (100 levels)
 */
@Serializable
data class UserData(
    val id: Int,
    val name: String,
    val email: String,
    val active: Boolean
)

@OptIn(ExperimentalSerializationApi::class)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
abstract class BaseDecodeBenchmark {

    lateinit var smallJson: String
    lateinit var parseToClassJson: String
    lateinit var parseToClassJsonBuffer: Buffer
    lateinit var largeKeyValueJson: String
    lateinit var largeKeyValueWithEscapeJson: String
    lateinit var manyKeysJson: String
    lateinit var nestedDeeplyJson: String

    lateinit var smallJsonBuffer: Buffer
    lateinit var largeKeyValueJsonBuffer: Buffer
    lateinit var largeKeyValueWithEscapeJsonBuffer: Buffer
    lateinit var manyKeysJsonBuffer: Buffer
    lateinit var nestedDeeplyJsonBuffer: Buffer

    private val json = Json { ignoreUnknownKeys = true }

    @Setup
    open fun setup() {
        // Small JSON (3 fields)
        smallJson = """{   "name":  "John",  "age":   30  ,  "active"  :  true}"""

        // Large key/value strings (no escapes)
        largeKeyValueJson = """{"${"large".repeat(5000)}key":"${"large".repeat(5000)}value"}"""

        // Large key/value strings with escape sequences
        largeKeyValueWithEscapeJson =
            """{"${"\\nlarge".repeat(5000)}key":"${"\\u0020\\tlarge".repeat(5000)}value"}"""

        // Many keys (200 keys)
        manyKeysJson = buildString {
            append("{")
            repeat(200) { i ->
                if (i > 0) append(",")
                append(""""key$i":  "value$i"""")
            }
            append("}")
        }

        // Deeply nested JSON (100 levels)
        nestedDeeplyJson = buildString {
            repeat(100) { i -> append("""{"${i}level$i": """) }
            append(""""bottom"""")
            repeat(100) { append("}") }
        }

        // JSON with known fields + unknown keys to skip (for parsing to class)
        parseToClassJson = buildString {
            append("""{"id": 123, "name": "John Doe", """)
            // Add 20 unknown keys with various value types
            repeat(200) { i ->
                append(""""unknown$i": """)
                when (i % 4) {
                    0 -> append(""""string value $i"""")
                    1 -> append(i * 100)
                    2 -> append("""{"nested": "object", "num": $i}""")
                    3 -> append("""[1, 2, 3, "array"]""")
                }
                append(", ")
            }
            append(""""email": "john@example.com", "active": true}""")
        }

        // Initialize buffers
        smallJsonBuffer = Buffer().apply { writeUtf8(smallJson) }
        parseToClassJsonBuffer = Buffer().apply { writeUtf8(parseToClassJson) }
        largeKeyValueJsonBuffer = Buffer().apply { writeUtf8(largeKeyValueJson) }
        largeKeyValueWithEscapeJsonBuffer =
            Buffer().apply { writeUtf8(largeKeyValueWithEscapeJson) }
        manyKeysJsonBuffer = Buffer().apply { writeUtf8(manyKeysJson) }
        nestedDeeplyJsonBuffer = Buffer().apply { writeUtf8(nestedDeeplyJson) }
    }

    // ==================== Small JSON ====================

    @Benchmark
    open fun smallJson_kotlinx(): JsonObject = runBlocking {
        val buffer = Buffer()
        smallJsonBuffer.copyTo(buffer)
        json.decodeFromBufferedSource(JsonObject.serializer(), buffer).also {
            check(it.size == 3) { "smallJson should have 3 keys, got ${it.size}" }
        }
    }

    @Benchmark
    open fun smallJson_readObject(): JsonObject = runBlocking {
        val source = BufferedAsyncSource(AsyncSource.EMPTY)
        smallJsonBuffer.copyTo(source.buffer)
        JsonReader(source).readObject().also {
            check(it.size == 3) { "smallJson should have 3 keys, got ${it.size}" }
        }
    }

    @Benchmark
    open fun smallJson_stepByStep(): JsonObject = runBlocking {
        val source = BufferedAsyncSource(AsyncSource.EMPTY)
        smallJsonBuffer.copyTo(source.buffer)
        val reader = JsonReader(source)
        val map = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        reader.beginObject()
        while (reader.hasNextObjectKey()) {
            val key = reader.nextKey()
            val value = reader.nextValue()
            map[key] = value
        }
        reader.endObject()
        JsonObject(map).also {
            check(it.size == 3) { "smallJson should have 3 keys, got ${it.size}" }
        }
    }

    // ==================== Large Key/Value ====================

    @Benchmark
    open fun largeKeyValue_kotlinx(): JsonObject = runBlocking {
        val buffer = Buffer()
        largeKeyValueJsonBuffer.copyTo(buffer)
        json.decodeFromBufferedSource(JsonObject.serializer(), buffer).also {
            check(it.size == 1) { "largeKeyValue should have 1 key, got ${it.size}" }
        }
    }

    @Benchmark
    open fun largeKeyValue_readObject(): JsonObject = runBlocking {
        val source = BufferedAsyncSource(AsyncSource.EMPTY)
        largeKeyValueJsonBuffer.copyTo(source.buffer)
        JsonReader(source).readObject().also {
            check(it.size == 1) { "largeKeyValue should have 1 key, got ${it.size}" }
        }
    }

    @Benchmark
    open fun largeKeyValue_stepByStep(): JsonObject = runBlocking {
        val source = BufferedAsyncSource(AsyncSource.EMPTY)
        largeKeyValueJsonBuffer.copyTo(source.buffer)
        val reader = JsonReader(source)
        val map = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        reader.beginObject()
        while (reader.hasNextObjectKey()) {
            val key = reader.nextKey()
            val value = reader.nextValue()
            map[key] = value
        }
        reader.endObject()
        JsonObject(map).also {
            check(it.size == 1) { "largeKeyValue should have 1 key, got ${it.size}" }
        }
    }

    // ==================== Large Key/Value with Escapes ====================

    @Benchmark
    open fun largeKeyValueEscape_kotlinx(): JsonObject = runBlocking {
        val buffer = Buffer()
        largeKeyValueWithEscapeJsonBuffer.copyTo(buffer)
        json.decodeFromBufferedSource(JsonObject.serializer(), buffer).also {
            check(it.size == 1) { "largeKeyValueEscape should have 1 key, got ${it.size}" }
        }
    }

    @Benchmark
    open fun largeKeyValueEscape_readObject(): JsonObject = runBlocking {
        val source = BufferedAsyncSource(AsyncSource.EMPTY)
        largeKeyValueWithEscapeJsonBuffer.copyTo(source.buffer)
        JsonReader(source).readObject().also {
            check(it.size == 1) { "largeKeyValueEscape should have 1 key, got ${it.size}" }
        }
    }

    @Benchmark
    open fun largeKeyValueEscape_stepByStep(): JsonObject = runBlocking {
        val source = BufferedAsyncSource(AsyncSource.EMPTY)
        largeKeyValueWithEscapeJsonBuffer.copyTo(source.buffer)
        val reader = JsonReader(source)
        val map = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        reader.beginObject()
        while (reader.hasNextObjectKey()) {
            val key = reader.nextKey()
            val value = reader.nextValue()
            map[key] = value
        }
        reader.endObject()
        JsonObject(map).also {
            check(it.size == 1) { "largeKeyValueEscape should have 1 key, got ${it.size}" }
        }
    }

    // ==================== Many Keys ====================

    @Benchmark
    open fun manyKeys_kotlinx(): JsonObject = runBlocking {
        val buffer = Buffer()
        manyKeysJsonBuffer.copyTo(buffer)
        json.decodeFromBufferedSource(JsonObject.serializer(), buffer).also {
            check(it.size == 200) { "manyKeys should have 200 keys, got ${it.size}" }
        }
    }

    @Benchmark
    open fun manyKeys_readObject(): JsonObject = runBlocking {
        val source = BufferedAsyncSource(AsyncSource.EMPTY)
        manyKeysJsonBuffer.copyTo(source.buffer)
        JsonReader(source).readObject().also {
            check(it.size == 200) { "manyKeys should have 200 keys, got ${it.size}" }
        }
    }

    @Benchmark
    open fun manyKeys_stepByStep(): JsonObject = runBlocking {
        val source = BufferedAsyncSource(AsyncSource.EMPTY)
        manyKeysJsonBuffer.copyTo(source.buffer)
        val reader = JsonReader(source)
        val map = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        reader.beginObject()
        while (reader.hasNextObjectKey()) {
            val key = reader.nextKey()
            val value = reader.nextValue()
            map[key] = value
        }
        reader.endObject()
        JsonObject(map).also {
            check(it.size == 200) { "manyKeys should have 200 keys, got ${it.size}" }
        }
    }

    // ==================== Nested Deeply ====================

    @Benchmark
    open fun nestedDeeply_kotlinx(): JsonObject = runBlocking {
        val buffer = Buffer()
        nestedDeeplyJsonBuffer.copyTo(buffer)
        json.decodeFromBufferedSource(JsonObject.serializer(), buffer).also {
            check(it.size == 1) { "nestedDeeply should have 1 key at root, got ${it.size}" }
        }
    }

    @Benchmark
    open fun nestedDeeply_readObject(): JsonObject = runBlocking {
        val source = BufferedAsyncSource(AsyncSource.EMPTY)
        nestedDeeplyJsonBuffer.copyTo(source.buffer)
        JsonReader(source).readObject().also {
            check(it.size == 1) { "nestedDeeply should have 1 key at root, got ${it.size}" }
        }
    }

    // ==================== Parse to Class (with unknown keys) ====================

    @Benchmark
    open fun parseToClass_kotlinx(): UserData = runBlocking {
        val buffer = Buffer()
        parseToClassJsonBuffer.copyTo(buffer)
        json.decodeFromBufferedSource(UserData.serializer(), buffer).also {
            check(it.id == 123) { "parseToClass id should be 123, got ${it.id}" }
        }
    }

    @Benchmark
    open fun parseToClass_readObject(): UserData = runBlocking {
        val source = BufferedAsyncSource(AsyncSource.EMPTY)
        parseToClassJsonBuffer.copyTo(source.buffer)
        val reader = JsonReader(source)
        val obj = reader.readObject()
        UserData(obj["id"]!!.jsonPrimitive.int, obj["name"]!!.jsonPrimitive.toString(), obj["email"]!!.jsonPrimitive.toString(), obj["active"]!!.jsonPrimitive.boolean).also {
            check(it.id == 123) { "parseToClass id should be 123, got ${it.id}" }
        }
    }

    @Benchmark
    open fun parseToClass_stepByStep(): UserData = runBlocking {
        val source = BufferedAsyncSource(AsyncSource.EMPTY)
        parseToClassJsonBuffer.copyTo(source.buffer)
        val reader = JsonReader(source)
        var id = 0
        var name = ""
        var email = ""
        var active = false
        reader.beginObject()
        while (reader.hasNextObjectKey()) {
            when (reader.nextKey()) {
                "id" -> id = reader.nextValue().jsonPrimitive.int
                "name" -> name = reader.nextValue().jsonPrimitive.content
                "email" -> email = reader.nextValue().jsonPrimitive.content
                "active" -> active = reader.nextValue().jsonPrimitive.boolean
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        UserData(id, name, email, active).also {
            check(it.id == 123) { "parseToClass id should be 123, got ${it.id}" }
        }
    }
}
