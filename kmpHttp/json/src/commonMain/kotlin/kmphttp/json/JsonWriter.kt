package kmphttp.json

import kmpcommon.io.AsyncSink
import okio.Buffer

/**
 * Streaming JSON encoder — the write-side mirror of [JsonReader]. Emits compact JSON into an
 * [AsyncSink] through an internal [Buffer] that drains once it grows past [flushThreshold], so a
 * large document never materializes whole. Not thread-safe; drive it from one coroutine.
 *
 * The caller MUST [flush] when done — buffered bytes reach the sink only on a flush (or when the
 * buffer crosses the threshold mid-write).
 *
 * Shape is checked as it is written: [name] only inside an object, each object [name] followed by
 * exactly one value, one root value per document, matched begin/end.
 */
class JsonWriter(
    private val sink: AsyncSink,
    private val flushThreshold: Long = 8192,
) {
    private val buffer = Buffer()
    private val stack = ArrayDeque<Scope>()
    private var rootWritten = false

    private class Scope(val isObject: Boolean) {
        var count = 0 // pairs (object) / elements (array) written so far
        var pendingName = false // object: a name is written, its value is due next
    }

    suspend fun beginObject(): JsonWriter = open('{', isObject = true)

    suspend fun endObject(): JsonWriter {
        val scope = stack.removeLastOrNull() ?: error("endObject() without beginObject()")
        check(scope.isObject) { "endObject() closing an array" }
        check(!scope.pendingName) { "endObject() after name() with no value" }
        buffer.writeByte('}'.code)
        return flushIfLarge()
    }

    suspend fun beginArray(): JsonWriter = open('[', isObject = false)

    suspend fun endArray(): JsonWriter {
        val scope = stack.removeLastOrNull() ?: error("endArray() without beginArray()")
        check(!scope.isObject) { "endArray() closing an object" }
        buffer.writeByte(']'.code)
        return flushIfLarge()
    }

    /** Write an object key and its ':'; the next call must write the value. */
    suspend fun name(name: String): JsonWriter {
        val scope = stack.lastOrNull()
        check(scope != null && scope.isObject) { "name() only inside an object" }
        check(!scope.pendingName) { "name() twice without a value" }
        if (scope.count > 0) buffer.writeByte(','.code)
        scope.count++
        scope.pendingName = true
        writeString(name)
        buffer.writeByte(':'.code)
        return flushIfLarge()
    }

    suspend fun value(value: String?): JsonWriter {
        beforeValue()
        if (value == null) buffer.writeUtf8("null") else writeString(value)
        return flushIfLarge()
    }

    suspend fun value(value: Long): JsonWriter {
        beforeValue()
        buffer.writeUtf8(value.toString())
        return flushIfLarge()
    }

    suspend fun value(value: Int): JsonWriter = value(value.toLong())

    suspend fun value(value: Boolean): JsonWriter {
        beforeValue()
        buffer.writeUtf8(if (value) "true" else "false")
        return flushIfLarge()
    }

    suspend fun nullValue(): JsonWriter {
        beforeValue()
        buffer.writeUtf8("null")
        return flushIfLarge()
    }

    /** Drain everything buffered to the sink and flush it. */
    suspend fun flush() {
        if (buffer.size > 0) sink.write(buffer, buffer.size)
        sink.flush()
    }

    private suspend fun open(brace: Char, isObject: Boolean): JsonWriter {
        beforeValue()
        buffer.writeByte(brace.code)
        stack.addLast(Scope(isObject))
        return flushIfLarge()
    }

    // Emit the separator a value needs in the current context, and advance the book-keeping. Top
    // level accepts exactly one value.
    private fun beforeValue() {
        val scope = stack.lastOrNull()
        if (scope == null) {
            check(!rootWritten) { "a JSON document has a single root value" }
            rootWritten = true
            return
        }
        if (scope.isObject) {
            check(scope.pendingName) { "a value in an object must follow name()" }
            scope.pendingName = false
        } else {
            if (scope.count > 0) buffer.writeByte(','.code)
            scope.count++
        }
    }

    private suspend fun flushIfLarge(): JsonWriter {
        if (buffer.size >= flushThreshold) sink.write(buffer, buffer.size)
        return this
    }

    // Escape only what JSON requires: the quote, the backslash, and control chars < 0x20 (short forms
    // for \b \t \n \f \r, \u00XX otherwise). Everything else — non-ASCII and astral included — is raw
    // UTF-8 (okio's writeUtf8 encodes surrogate pairs, and a run never splits a pair since every
    // escaped char is BMP).
    private fun writeString(s: String) {
        buffer.writeByte('"'.code)
        var runStart = 0
        var i = 0
        val n = s.length
        while (i < n) {
            val escape: String? = when (val c = s[i]) {
                '"' -> "\\\""
                '\\' -> "\\\\"
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                '\b' -> "\\b"
                '\u000C' -> "\\f"
                else -> if (c.code < 0x20) unicodeEscape(c.code) else null
            }
            if (escape != null) {
                if (i > runStart) buffer.writeUtf8(s, runStart, i)
                buffer.writeUtf8(escape)
                runStart = i + 1
            }
            i++
        }
        if (n > runStart) buffer.writeUtf8(s, runStart, n)
        buffer.writeByte('"'.code)
    }

    private companion object {
        private const val HEX = "0123456789abcdef"

        // code is < 0x20 here, so the two high nibbles are always 0.
        private fun unicodeEscape(code: Int): String =
            "\\u00" + HEX[(code shr 4) and 0xF] + HEX[code and 0xF]
    }
}
