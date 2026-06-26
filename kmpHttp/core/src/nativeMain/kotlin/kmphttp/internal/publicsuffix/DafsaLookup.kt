/*
 * DAFSA (Directed Acyclic Finite State Automaton) lookup implementation.
 * Ported from Chromium's lookup_string_in_fixed_set.c
 *
 * Copyright 2015-2016 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license.
 */
package kmphttp.internal.publicsuffix

/** Result codes for DAFSA lookup */
internal const val DAFSA_NOT_FOUND = -1
internal const val DAFSA_FOUND = 0
internal const val DAFSA_EXCEPTION_RULE = 1
internal const val DAFSA_WILDCARD_RULE = 2
internal const val DAFSA_PRIVATE_RULE = 4

/** Multibyte length table for UTF-8 sequences */
private val MULTIBYTE_LENGTH_TABLE = intArrayOf(
    0, 0, 0, 0, // 0x00-0x3F
    0, 0, 0, 0, // 0x40-0x7F
    0, 0, 0, 0, // 0x80-0xBF
    2, 2, 3, 4  // 0xC0-0xFF
)

/** Get length of multibyte character sequence starting at a given byte */
private fun getMultibyteLength(c: Byte): Int {
    return MULTIBYTE_LENGTH_TABLE[(c.toInt() and 0xFF) shr 4]
}

/** State for tracking position during traversal */
private class LookupState(
    val graph: ByteArray,
    var pos: Int,
    val end: Int,
    var offset: Int,
    val key: CharSequence,
    var keyPos: Int,
    var multibyteStart: Int // -1 if not in multibyte mode
) {
    fun atEnd() = keyPos >= key.length
    fun currentKeyByte(): Byte = key[keyPos].code.toByte()
}

/** Read next offset from current position */
private fun getNextOffset(state: LookupState): Boolean {
    if (state.pos >= state.end) return false

    // Need at least 3 more bytes (offset + node to skip + destination)
    if (state.pos + 2 >= state.end) return false

    val b = state.graph[state.pos].toInt() and 0xFF
    val bytesConsumed: Int

    when (b and 0x60) {
        0x60 -> { // 3-byte offset
            val b1 = state.graph[state.pos + 1].toInt() and 0xFF
            val b2 = state.graph[state.pos + 2].toInt() and 0xFF
            state.offset += ((b and 0x1F) shl 16) or (b1 shl 8) or b2
            bytesConsumed = 3
        }
        0x40 -> { // 2-byte offset
            val b1 = state.graph[state.pos + 1].toInt() and 0xFF
            state.offset += ((b and 0x1F) shl 8) or b1
            bytesConsumed = 2
        }
        else -> { // 1-byte offset
            state.offset += b and 0x3F
            bytesConsumed = 1
        }
    }

    if ((b and 0x80) != 0) {
        state.pos = state.end // Terminate traversal
    } else {
        state.pos += bytesConsumed
    }
    return true
}

/** Check if byte at offset is last in label (has high bit set) */
private fun isEOL(graph: ByteArray, offset: Int, end: Int): Boolean {
    if (offset >= end) return true
    return (graph[offset].toInt() and 0x80) != 0
}

/** Check if byte matches character (not last in label) */
private fun isMatch(graph: ByteArray, offset: Int, end: Int, key: CharSequence, keyPos: Int, multibyteStart: Int): Boolean {
    if (offset >= end) return false
    return isMatchUnchecked(graph[offset], key, keyPos, multibyteStart)
}

/** Core matching logic */
private fun isMatchUnchecked(matcher: Byte, key: CharSequence, keyPos: Int, multibyteStart: Int): Boolean {
    val m = matcher.toInt() and 0xFF
    val k = key[keyPos].code and 0xFF

    if (multibyteStart >= 0) {
        // Multibyte matching mode
        return if (multibyteStart == keyPos) {
            // Match leading byte
            (m xor 0x80) == k
        } else {
            // Match following bytes
            (m xor 0xC0) == k
        }
    }

    // Check if key points at a leading byte in a multibyte sequence
    if (getMultibyteLength(k.toByte()) > 0) {
        return m == 0x1F // Mode switch byte
    }

    // Normal single byte character match
    return m == k
}

/** Check if byte matches character (last in label, XOR with 0x80) */
private fun isEndCharMatch(graph: ByteArray, offset: Int, end: Int, key: CharSequence, keyPos: Int, multibyteStart: Int): Boolean {
    if (offset >= end) return false
    val matcher = graph[offset]
    val m = matcher.toInt() and 0xFF
    val k = key[keyPos].code and 0xFF

    if (multibyteStart >= 0) {
        return if (multibyteStart == keyPos) {
            m == k
        } else {
            (m xor 0x40) == k
        }
    }

    if (getMultibyteLength(k.toByte()) > 0) {
        return m == 0x9F // 0x1F | 0x80
    }

    return (m xor 0x80) == k
}

/** Advance position after consuming a character */
private fun nextPos(state: LookupState) {
    state.offset++
    if (state.multibyteStart >= 0) {
        state.keyPos++
        val mbLen = getMultibyteLength(state.key[state.multibyteStart].code.toByte())
        if (state.keyPos - state.multibyteStart >= mbLen) {
            state.multibyteStart = -1
        }
    } else {
        if (getMultibyteLength(state.key[state.keyPos].code.toByte()) > 0) {
            state.multibyteStart = state.keyPos
        } else {
            state.keyPos++
        }
    }
}

/** Get return value from current position */
private fun getReturnValue(graph: ByteArray, offset: Int, end: Int, multibyteStart: Int): Int? {
    if (offset >= end) return null
    val b = graph[offset].toInt() and 0xFF

    // Return values are encoded as 0x80..0x8F for single byte chars
    if (multibyteStart < 0 && (b and 0xF0) == 0x80) {
        return b and 0x0F
    }
    return null
}

/** DAFSA binary header size: ".DAFSA@PSL_0   \n" */
private const val DAFSA_HEADER_SIZE = 16

/**
 * Look up a string in the DAFSA.
 *
 * @param graph The DAFSA byte array (with header)
 * @param key The domain string to look up (lowercase ASCII)
 * @return DAFSA_NOT_FOUND, DAFSA_FOUND, or a bitmap of rule flags
 */
internal fun lookupStringInDafsa(graph: ByteArray, key: CharSequence): Int {
    if (graph.size <= DAFSA_HEADER_SIZE || key.isEmpty()) return DAFSA_NOT_FOUND

    val state = LookupState(
        graph = graph,
        pos = DAFSA_HEADER_SIZE,  // Skip header
        end = graph.size,
        offset = DAFSA_HEADER_SIZE,  // Skip header
        key = key,
        keyPos = 0,
        multibyteStart = -1
    )

    while (getNextOffset(state)) {
        var didConsume = false
        val savedOffset = state.offset

        if (!state.atEnd() && !isEOL(graph, state.offset, state.end)) {
            // Leading char is not a match, skip this child
            if (!isMatch(graph, state.offset, state.end, state.key, state.keyPos, state.multibyteStart)) {
                continue
            }
            didConsume = true
            nextPos(state)

            // Match remaining chars in label
            while (!isEOL(graph, state.offset, state.end) && !state.atEnd()) {
                if (!isMatch(graph, state.offset, state.end, state.key, state.keyPos, state.multibyteStart)) {
                    return DAFSA_NOT_FOUND
                }
                nextPos(state)
            }
        }

        if (state.atEnd()) {
            val returnValue = getReturnValue(graph, state.offset, state.end, state.multibyteStart)
            if (returnValue != null) return returnValue
            if (didConsume) return DAFSA_NOT_FOUND
            continue
        }

        if (!isEndCharMatch(graph, state.offset, state.end, state.key, state.keyPos, state.multibyteStart)) {
            if (didConsume) return DAFSA_NOT_FOUND
            continue
        }

        nextPos(state)
        state.pos = state.offset // Dive into child
    }

    return DAFSA_NOT_FOUND
}
