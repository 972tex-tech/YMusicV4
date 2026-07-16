package kmphttp.json

import kmpcommon.io.BufferedAsyncSource
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import okio.Buffer
import okio.ByteString
import kotlin.math.max

class JsonReader(val source: BufferedAsyncSource, val lenient: Boolean = false) {
    private val buffer = source.buffer

    companion object {
        private val terminators = ByteString.of(
            ','.code.toByte(), ' '.code.toByte(),
            '\n'.code.toByte(), '\r'.code.toByte(),
            '}'.code.toByte(), ']'.code.toByte(),
            '\t'.code.toByte(),
        )

        private val slashOrDoubleQuote = ByteString.of(
            '"'.code.toByte(), '\\'.code.toByte()
        )

        private val slashOrSingleQuote = ByteString.of(
            '\''.code.toByte(), '\\'.code.toByte()
        )

        private const val PEEKED_NONE = -1

        private const val STEP_PEEK_NEXT_VALUE: Byte = 0

        private const val STEP_BEGIN_OBJ: Byte = 1
        private const val STEP_OBJ_CHECK_NEXT_KEY: Byte = 2
        private const val STEP_OBJ_READ_NEXT_KEY: Byte = 3
        private const val STEP_OBJ_READ_COLON: Byte = 4
        private const val STEP_END_OBJ: Byte = 5

        private const val STEP_ARRAY_BEGIN: Byte = 6
        private const val STEP_ARRAY_CHECK_NEXT_VALUE: Byte = 7
        private const val STEP_END_ARRAY: Byte = 8

        private const val STEP_NEXT_STRING: Byte = 9
        private const val STEP_NEXT_UNQUOTED_LITERAL: Byte = 10

        private fun isWhiteSpace(char: Byte): Boolean {
            return char == ' '.code.toByte()
                || char == '\n'.code.toByte()
                || char == '\r'.code.toByte()
                || char == '\t'.code.toByte()
        }
    }

    private var peekedByte: Int = PEEKED_NONE
    private var bytesNeeded: Int = 0
    private var sourceEof: Boolean = false

    /** Track if we just read a value (expecting comma or close bracket next). */
    private var expectSeparator: Boolean = false

    // ==================== Internal non-suspend primitives ====================

    private fun tryPeek(): Boolean {
        if (peekedByte > PEEKED_NONE) return true
        // skip whitespace
        while (buffer.size > 0 && isWhiteSpace(buffer[0])) {
            buffer.skip(1)
        }
        if (buffer.size > 0) {
            peekedByte = buffer[0].toInt() and 0xFF
            return true
        }
        return false
    }

    private fun expect(c: Char): Boolean {
        if (!tryPeek()) {
            if (sourceEof) throw JsonSyntaxException("Expected '$c' but EOF")
            return false
        }
        val actual = peekedByte
        if (peekedByte == PEEKED_NONE) throw IllegalStateException("Not peeked yet")
        if (actual != c.code) throw JsonSyntaxException("Expected '$c' but got '${actual.toChar()}'")
        consume()
        return true
    }

    private fun consume() {
        buffer.skip(1)
        peekedByte = PEEKED_NONE
    }

    private suspend inline fun tryLoadMore() {
        if (sourceEof) return
        if (!source.request(buffer.size + max(bytesNeeded, 1))) {
            sourceEof = true
            return
        }
        bytesNeeded = 0
    }

    private suspend inline fun peekSuspend(): Int {
        while (!tryPeek()) {
            if (sourceEof) throw JsonSyntaxException("Unexpected EOF")
            tryLoadMore()
        }

        return peekedByte
    }

    private suspend inline fun expectSuspend(c: Char) {
        while (!tryPeek()) {
            if (sourceEof) throw JsonSyntaxException("Expected '$c' but EOF")
            tryLoadMore()
        }

        val actual = peekedByte
        if (actual != c.code) {
            throw JsonSyntaxException("Expected '$c' but got '${actual.toChar()}'")
        }
        consume()
    }

    private val readStringState =
        ReadStringState(false, '"'.code.toByte(), slashOrDoubleQuote, Buffer())

    private fun readString(skipping: Boolean = false): String? {
        val state = readStringState
        if (!state.started) {
            check(state.buffer.size == 0L)
            if (!tryPeek()) {
                if (sourceEof) {
                    throw JsonSyntaxException("Expected string but EOF")
                }
                bytesNeeded = 2
                return null
            }
            val p = peekedByte
            if (p != '"'.code && (p != '\''.code || !lenient)) {
                throw JsonSyntaxException("Expected string but got '${p.toChar()}'")
            }
            consume()
            state.started = true
            state.quoteByte = p.toByte()
            state.quoteOrSlash =
                if (p == '"'.code) slashOrDoubleQuote
                else slashOrSingleQuote
        }
        while (true) {
            val indexOf = buffer.indexOfElement(state.quoteOrSlash)
            if (indexOf == -1L) {
                if (sourceEof) throw JsonSyntaxException("Unterminated string")
                if (buffer.size > 0) {
                    if (skipping) {
                        buffer.clear()
                    } else {
                        state.buffer.write(buffer, buffer.size)
                    }
                }
                bytesNeeded = 1
                return null
            }
            when (buffer[indexOf]) {
                state.quoteByte -> {
                    state.started = false
                    if (skipping) {
                        buffer.skip(indexOf + 1)
                        return ""
                    }
                    val result = if (state.buffer.size == 0L) {
                        buffer.readUtf8(indexOf)
                    } else {
                        state.buffer.write(buffer, indexOf)
                        state.buffer.readUtf8()
                    }
                    buffer.skip(1)
                    return result
                }

                '\\'.code.toByte() -> {
                    if (skipping) {
                        buffer.skip(indexOf)
                    } else {
                        state.buffer.write(buffer, indexOf)
                    }
                    if (buffer.size < 2) {
                        if (sourceEof) throw JsonSyntaxException("Unterminated string")
                        bytesNeeded = 2 + 1
                        return null
                    }
                    when (val c = buffer[1].toInt().toChar()) {
                        'n' -> {
                            if (!skipping) state.buffer.writeByte('\n'.code)
                            buffer.skip(2)
                        }

                        'r' -> {
                            if (!skipping) state.buffer.writeByte('\r'.code)
                            buffer.skip(2)
                        }

                        'b' -> {
                            if (!skipping) state.buffer.writeByte('\b'.code)
                            buffer.skip(2)
                        }

                        't' -> {
                            if (!skipping) state.buffer.writeByte('\t'.code)
                            buffer.skip(2)
                        }

                        'f' -> {
                            if (!skipping) state.buffer.writeByte('\u000C'.code)
                            buffer.skip(2)
                        }

                        'u' -> {
                            if (buffer.size < 6) {
                                if (sourceEof) {
                                    throw JsonSyntaxException("Invalid unicode escape ${buffer.readUtf8()}")
                                }
                                bytesNeeded = 6 + 1
                                return null
                            }
                            if (skipping) {
                                buffer.skip(6)
                            } else {
                                val codePoint = hex4OrNull(buffer, 2)
                                    ?: throw JsonSyntaxException(buffer.readUtf8(6))
                                var toWrite = codePoint
                                var consumed = 6L
                                if (codePoint in 0xD800..0xDBFF) {
                                    // Astral chars arrive as a surrogate PAIR; combine the halves,
                                    // since writeUtf8CodePoint rejects a lone surrogate (writes '?').
                                    if (buffer.size < 12 && !sourceEof) {
                                        bytesNeeded = 12 + 1 // need the (potential) low half too
                                        return null
                                    }
                                    if (buffer.size >= 12 &&
                                        buffer[6] == '\\'.code.toByte() &&
                                        buffer[7] == 'u'.code.toByte()
                                    ) {
                                        val lo = hex4OrNull(buffer, 8)
                                        if (lo != null && lo in 0xDC00..0xDFFF) {
                                            toWrite = 0x10000 +
                                                ((codePoint - 0xD800) shl 10) + (lo - 0xDC00)
                                            consumed = 12L
                                        }
                                        // Not a low surrogate: fall through — the lone high half
                                        // becomes '?' (as before) and the next escape is
                                        // processed, and validated, on its own.
                                    }
                                }
                                state.buffer.writeUtf8CodePoint(toWrite)
                                buffer.skip(consumed)
                            }
                        }

                        else -> {
                            if (!skipping) state.buffer.writeByte(c.code)
                            buffer.skip(2)
                        }
                    }
                }

                else -> throw IllegalStateException("???")
            }
        }
    }

    /** The 4 hex digits at [at]..[at]+3 of [buffer], or null if any is not a hex digit. */
    private fun hex4OrNull(buffer: Buffer, at: Long): Int? {
        var value = 0
        for (i in 0L until 4) {
            value = value shl 4
            value += when (val c = buffer[at + i].toInt().toChar()) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                in 'A'..'F' -> c - 'A' + 10
                else -> return null
            }
        }
        return value
    }

    private fun peekTokenTypeInternal(): JsonTokenType? {
        if (!tryPeek()) {
            if (sourceEof) throw JsonSyntaxException("Expected json value but EOF")
            return null
        }
        return when (peekedByte) {
            '"'.code, '\''.code -> JsonTokenType.STRING
            '{'.code -> JsonTokenType.OBJECT
            '['.code -> JsonTokenType.ARRAY
            else -> JsonTokenType.UNQUOTED_LITERAL
        }
    }

    private fun readLiteral(skipping: Boolean = false): String? {
        peekedByte = PEEKED_NONE
        val end = buffer.indexOfElement(terminators)
        if (end == -1L) {
            if (skipping) buffer.clear()
            if (sourceEof) throw JsonSyntaxException("Expected unquoted literal but EOF")
            return null
        }
        if (skipping) {
            buffer.skip(end)
            return ""
        } else {
            return buffer.readUtf8(end)
        }
    }

    /** State for the nextValueInternal state machine */
    private val valueStack = mutableListOf<ReaderState>()
    private var nextValueStep = STEP_PEEK_NEXT_VALUE

    private fun checkNextValueCompleted() {
        check(valueStack.isEmpty() && nextValueStep == STEP_PEEK_NEXT_VALUE)
    }

    /**
     * Non-recursive, non-suspend nextValue implementation with predictable state machine code.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun nextValueInternal(expectType: JsonTokenType? = null): JsonElement? {
        val stack = valueStack
        do {
            when (nextValueStep) {
                STEP_PEEK_NEXT_VALUE -> {
                    val valueType = peekTokenTypeInternal() ?: return null
                    if (stack.isEmpty() && expectType != null) {
                        check(valueType == expectType) {
                            "Expect json value type $expectType but actual $valueType"
                        }
                    }
                    nextValueStep = when (valueType) {
                        JsonTokenType.OBJECT -> STEP_BEGIN_OBJ
                        JsonTokenType.ARRAY -> STEP_ARRAY_BEGIN
                        JsonTokenType.STRING -> STEP_NEXT_STRING
                        JsonTokenType.UNQUOTED_LITERAL -> STEP_NEXT_UNQUOTED_LITERAL
                    }
                }

                STEP_BEGIN_OBJ -> {
                    if (!expect('{')) return null
                    stack.add(ReaderObjectState())
                    expectSeparator = false
                    nextValueStep = STEP_OBJ_CHECK_NEXT_KEY
                }

                STEP_OBJ_CHECK_NEXT_KEY -> {
                    if (!tryPeek()) return null
                    when (peekedByte) {
                        ','.code -> {
                            if (expectSeparator) {
                                consume()
                                expectSeparator = false
                            } else {
                                throw JsonSyntaxException("Unexpected ','")
                            }
                        }

                        '}'.code -> {
                            nextValueStep = STEP_END_OBJ
                            continue
                        }
                    }
                    nextValueStep = STEP_OBJ_READ_NEXT_KEY
                }

                STEP_OBJ_READ_NEXT_KEY -> {
                    val key = readString() ?: return null
                    (stack.last() as ReaderObjectState).pushKey(key)
                    nextValueStep = if (expect(':')) {
                        STEP_PEEK_NEXT_VALUE
                    } else {
                        STEP_OBJ_READ_COLON
                    }
                }

                STEP_OBJ_READ_COLON -> {
                    if (!expect(':')) return null
                    nextValueStep = STEP_PEEK_NEXT_VALUE
                }

                STEP_END_OBJ -> {
                    if (!expect('}')) return null
                    expectSeparator = true
                    val objState = stack.removeAt(stack.lastIndex) as ReaderObjectState
                    val obj = objState.end()
                    if (stack.isEmpty()) {
                        nextValueStep = STEP_PEEK_NEXT_VALUE
                        return obj
                    }
                    nextValueStep = stack.last().pushValue(obj)
                }

                STEP_ARRAY_BEGIN -> {
                    if (!expect('[')) return null
                    stack.add(ReaderArrayState())
                    expectSeparator = false
                    nextValueStep = STEP_ARRAY_CHECK_NEXT_VALUE
                }

                STEP_ARRAY_CHECK_NEXT_VALUE -> {
                    if (!tryPeek()) return null
                    when (peekedByte) {
                        ','.code -> {
                            if (expectSeparator) {
                                consume()
                                expectSeparator = false
                            } else {
                                throw JsonSyntaxException("Unexpected '[,' sequence")
                            }
                        }

                        ']'.code -> {
                            nextValueStep = STEP_END_ARRAY
                            continue
                        }
                    }
                    nextValueStep = STEP_PEEK_NEXT_VALUE
                }

                STEP_END_ARRAY -> {
                    if (!expect(']')) return null

                    expectSeparator = true
                    val arrayState = stack.removeAt(stack.lastIndex) as ReaderArrayState
                    val arr = arrayState.end()
                    if (stack.isEmpty()) {
                        nextValueStep = STEP_PEEK_NEXT_VALUE
                        return arr
                    }
                    nextValueStep = stack.last().pushValue(arr)
                }

                STEP_NEXT_STRING -> {
                    val str = readString() ?: return null
                    val jsonStr = JsonPrimitive(str)
                    expectSeparator = true
                    if (stack.isEmpty()) {
                        nextValueStep = STEP_PEEK_NEXT_VALUE
                        return jsonStr
                    }
                    nextValueStep = stack.last().pushValue(jsonStr)
                }

                STEP_NEXT_UNQUOTED_LITERAL -> {
                    val literal = readLiteral() ?: return null
                    val jsonLiteral = if (literal.contentEquals("null", ignoreCase = lenient)) {
                        JsonNull
                    } else {
                        JsonUnquotedLiteral(literal)
                    }
                    expectSeparator = true
                    if (stack.isEmpty()) {
                        nextValueStep = STEP_PEEK_NEXT_VALUE
                        return jsonLiteral
                    }
                    nextValueStep = stack.last().pushValue(jsonLiteral)
                }

                else -> throw IllegalStateException("Invalid nv step $nextValueStep")
            }
        } while (true)
    }

    private val skipStack = mutableListOf<Byte>()
    private var skipStep = STEP_PEEK_NEXT_VALUE
    private fun checkSkipValueCompleted() {
        check(skipStack.isEmpty() && skipStep == STEP_PEEK_NEXT_VALUE)
    }

    private fun skipValueInternal(): Unit? {
        val stack = skipStack
        do {
            when (skipStep) {
                STEP_PEEK_NEXT_VALUE -> {
                    val valueType = peekTokenTypeInternal() ?: return null
                    skipStep = when (valueType) {
                        JsonTokenType.OBJECT -> STEP_BEGIN_OBJ
                        JsonTokenType.ARRAY -> STEP_ARRAY_BEGIN
                        JsonTokenType.STRING -> STEP_NEXT_STRING
                        JsonTokenType.UNQUOTED_LITERAL -> STEP_NEXT_UNQUOTED_LITERAL
                    }
                }

                STEP_BEGIN_OBJ -> {
                    if (!expect('{')) return null

                    stack.add(STEP_OBJ_CHECK_NEXT_KEY)
                    expectSeparator = false
                    skipStep = STEP_OBJ_CHECK_NEXT_KEY
                }

                STEP_OBJ_CHECK_NEXT_KEY -> {
                    if (!tryPeek()) return null
                    when (peekedByte) {
                        ','.code -> {
                            if (expectSeparator) {
                                consume()
                                expectSeparator = false
                            } else {
                                throw JsonSyntaxException("Unexpected ','")
                            }
                        }

                        '}'.code -> {
                            skipStep = STEP_END_OBJ
                            continue
                        }
                    }
                    skipStep = STEP_OBJ_READ_NEXT_KEY
                }

                STEP_OBJ_READ_NEXT_KEY -> {
                    readString(skipping = true) ?: return null
                    skipStep = if (expect(':')) {
                        STEP_PEEK_NEXT_VALUE
                    } else {
                        STEP_OBJ_READ_COLON
                    }
                }

                STEP_OBJ_READ_COLON -> {
                    if (!expect(':')) return null
                    skipStep = STEP_PEEK_NEXT_VALUE
                }

                STEP_END_OBJ -> {
                    if (!expect('}')) return null
                    expectSeparator = true
                    stack.removeAt(stack.lastIndex)
                    if (stack.isEmpty()) {
                        skipStep = STEP_PEEK_NEXT_VALUE
                        return Unit
                    }
                    skipStep = stack.last()
                }

                STEP_ARRAY_BEGIN -> {
                    if (!expect('[')) return null

                    stack.add(STEP_ARRAY_CHECK_NEXT_VALUE)
                    expectSeparator = false
                    skipStep = STEP_ARRAY_CHECK_NEXT_VALUE
                }

                STEP_ARRAY_CHECK_NEXT_VALUE -> {
                    if (!tryPeek()) return null
                    when (peekedByte) {
                        ','.code -> {
                            if (expectSeparator) {
                                consume()
                                expectSeparator = false
                            } else {
                                throw JsonSyntaxException("Unexpected '[,' sequence")
                            }
                            skipStep = STEP_PEEK_NEXT_VALUE
                        }

                        ']'.code -> {
                            skipStep = STEP_END_ARRAY
                        }

                        else -> {
                            skipStep = STEP_PEEK_NEXT_VALUE
                        }
                    }
                }

                STEP_END_ARRAY -> {
                    if (!expect(']')) return null
                    expectSeparator = true
                    stack.removeAt(stack.lastIndex)
                    if (stack.isEmpty()) {
                        skipStep = STEP_PEEK_NEXT_VALUE
                        return Unit
                    }
                    skipStep = stack.last()
                }

                STEP_NEXT_STRING -> {
                    readString(skipping = true) ?: return null
                    expectSeparator = true
                    if (stack.isEmpty()) {
                        skipStep = STEP_PEEK_NEXT_VALUE
                        return Unit
                    }
                    skipStep = stack.last()
                }

                STEP_NEXT_UNQUOTED_LITERAL -> {
                    readLiteral(skipping = true) ?: return null
                    expectSeparator = true
                    if (stack.isEmpty()) {
                        skipStep = STEP_PEEK_NEXT_VALUE
                        return Unit
                    }
                    skipStep = stack.last()
                }

                else -> throw IllegalStateException("Invalid skip step $skipStep")
            }
        } while (true)
    }

    suspend fun readObject(): JsonObject {
        return nextValue(JsonTokenType.OBJECT) as JsonObject
    }

    suspend fun readArray(): JsonArray {
        return nextValue(JsonTokenType.ARRAY) as JsonArray
    }

    suspend fun nextValue(expectType: JsonTokenType? = null): JsonElement {
        checkNextValueCompleted()
        var value = nextValueInternal(expectType)
        while (value == null) {
            tryLoadMore()
            value = nextValueInternal(expectType)
        }
        return value
    }

    suspend fun beginObject() {
        expectSuspend('{')
        expectSeparator = false
    }

    /** Expect and consume '}'. */
    suspend fun endObject() {
        expectSuspend('}')
        expectSeparator = true
    }

    suspend fun beginArray() {
        expectSuspend('[')
        expectSeparator = false
    }

    /** Expect and consume ']'. */
    suspend fun endArray() {
        expectSuspend(']')
        expectSeparator = true
    }

    /** Returns true if there is another key in the current object. Handles comma. */
    suspend fun hasNextObjectKey(): Boolean {
        val c = peekSuspend()
        return when {
            c == '}'.code -> false
            expectSeparator -> {
                if (c != ','.code) throw JsonSyntaxException("Expected ',' but got '${c.toChar()}'")
                consume()
                expectSeparator = false
                // After comma, must have a quoted key
                val next = peekSuspend()
                if (next != '"'.code && (next != '\''.code || !lenient)) {
                    throw JsonSyntaxException("Expected '\"' but got '${next.toChar()}'")
                }
                true
            }

            else -> true
        }
    }

    /** Returns true if there is another element in the current array. Handles comma. */
    suspend fun hasNextArrayElement(): Boolean {
        val c = peekSuspend()
        return when {
            c == ']'.code -> false
            expectSeparator -> {
                if (c != ','.code) throw JsonSyntaxException("Expected ',' but got '${c.toChar()}'")
                consume()
                expectSeparator = false
                // After comma, must have a value (not another comma or close)
                val next = peekSuspend()
                if (next == ','.code || next == ']'.code) throw JsonSyntaxException("Expected value but got '${next.toChar()}'")
                true
            }

            else -> true
        }
    }

    suspend fun peekNextTokenType(): JsonTokenType {
        return when (peekSuspend()) {
            '"'.code, '\''.code -> JsonTokenType.STRING
            '{'.code -> JsonTokenType.OBJECT
            '['.code -> JsonTokenType.ARRAY
            else -> JsonTokenType.UNQUOTED_LITERAL
        }
    }

    /** Read the next object key string and consume the following ':'. */
    suspend fun nextKey(): String {
        if (expectSeparator) {
            expectSuspend(',')
            expectSeparator = false
        }
        check(!readStringState.started)
        var key = readString()
        while (key == null) {
            tryLoadMore()
            key = readString()
        }
        expectSuspend(':')
        return key
    }

    suspend fun skipValue() {
        checkSkipValueCompleted()
        var result = skipValueInternal()
        while (result == null) {
            tryLoadMore()
            result = skipValueInternal()
        }
        expectSeparator = true
    }

    /** Assert that the stream is at EOF (with optional white spaces, no more JSON content). */
    suspend fun expectEof() {
        while (!tryPeek()) {
            if (sourceEof) {
                return
            }
            tryLoadMore()
        }
        throw JsonSyntaxException("Expected EOF but got '${peekedByte.toChar()}'")
    }

    /** Stack entry for non-recursive parsing */
    sealed interface ReaderState {
        fun pushValue(value: JsonElement): Byte
        fun end(): JsonElement
    }

    class ReaderObjectState : ReaderState {
        private val map = mutableMapOf<String, JsonElement>()
        private var pendingKey: String? = null

        fun pushKey(key: String) {
            check(this.pendingKey == null)
            this.pendingKey = key
        }

        override fun pushValue(value: JsonElement): Byte {
            val key = pendingKey ?: throw IllegalStateException("No pending key")
            map[key] = value
            pendingKey = null
            return STEP_OBJ_CHECK_NEXT_KEY
        }

        override fun end() = JsonObject(map)
    }

    class ReaderArrayState : ReaderState {
        private val list = mutableListOf<JsonElement>()

        override fun pushValue(value: JsonElement): Byte {
            list.add(value)
            return STEP_ARRAY_CHECK_NEXT_VALUE
        }

        override fun end() = JsonArray(list)
    }

    private class ReadStringState(
        var started: Boolean,
        var quoteByte: Byte,
        var quoteOrSlash: ByteString,
        val buffer: Buffer
    )
}

class JsonSyntaxException(message: String) : Exception(message)
