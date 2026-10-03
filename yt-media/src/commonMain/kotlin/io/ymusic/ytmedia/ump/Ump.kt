package io.ymusic.ytmedia.ump

import io.ymusic.ytmedia.proto.ProtoWriter
import kmpcommon.io.BufferedAsyncSource
import okio.BufferedSink
import okio.BufferedSource

/**
 * UMP part ids (`application/vnd.yt-ump`). See docs/player/sabr-protocol.md §2.
 * Unknown ids must be skipped by size — the framing is self-describing.
 */
object UmpPart {
    const val MEDIA_HEADER = 20
    const val MEDIA_DATA = 21 // payload: 1 byte headerId + media bytes
    const val MEDIA_END = 22 // payload: 1 byte headerId
    const val NEXT_REQUEST_POLICY = 35
    const val FORMAT_INITIALIZATION_METADATA = 42
    const val SABR_REDIRECT = 43
    const val SABR_ERROR = 44
    const val SABR_CONTEXT_UPDATE = 57
    const val STREAM_PROTECTION_STATUS = 58
}

/**
 * The UMP varint is NOT a protobuf varint: a UTF-8-style prefix byte selects the total size
 * (1–5 bytes), remaining value bytes are little-endian. Max value 2^32-1.
 */
fun BufferedSource.readUmpVarInt(): Long {
    val prefix = readByte().toInt() and 0xFF
    return when {
        prefix < 0x80 -> prefix.toLong()
        prefix < 0xC0 -> (prefix and 0x3F) + 64L * readUByte()
        prefix < 0xE0 -> (prefix and 0x1F) + 32L * (readUByte() + 256L * readUByte())
        prefix < 0xF0 -> (prefix and 0x0F) + 16L * (readUByte() + 256L * (readUByte() + 256L * readUByte()))
        else -> readUByte() + 256L * (readUByte() + 256L * (readUByte() + 256L * readUByte()))
    }
}

fun <S : BufferedSink> S.writeUmpVarInt(value: Long): S = apply {
    require(value in 0 until (1L shl 32)) { "UMP varint out of range: $value" }
    val v = value.toInt()
    when {
        value < 1L shl 7 -> writeByte(v)
        value < 1L shl 14 -> {
            writeByte((v and 0x3F) or 0x80)
            writeByte((v ushr 6) and 0xFF)
        }
        value < 1L shl 21 -> {
            writeByte((v and 0x1F) or 0xC0)
            writeByte((v ushr 5) and 0xFF)
            writeByte((v ushr 13) and 0xFF)
        }
        value < 1L shl 28 -> {
            writeByte((v and 0x0F) or 0xE0)
            writeByte((v ushr 4) and 0xFF)
            writeByte((v ushr 12) and 0xFF)
            writeByte((v ushr 20) and 0xFF)
        }
        else -> {
            writeByte(0xF0)
            writeByte(v and 0xFF)
            writeByte((v ushr 8) and 0xFF)
            writeByte((v ushr 16) and 0xFF)
            writeByte((v ushr 24) and 0xFF)
        }
    }
}

/** partId + payload size — the payload (exactly [size] bytes) follows in the stream. */
data class UmpPartHeader(val partId: Int, val size: Long)

fun BufferedSource.readUmpPartHeader(): UmpPartHeader {
    val partId = readUmpVarInt().toInt()
    val size = readUmpVarInt()
    return UmpPartHeader(partId, size)
}

fun <S : BufferedSink> S.writeUmpPartHeader(partId: Int, size: Long): S =
    writeUmpVarInt(partId.toLong()).writeUmpVarInt(size)

fun <S : BufferedSink> S.writeUmpPart(partId: Int, payload: ProtoWriter): S {
    writeUmpPartHeader(partId, payload.size)
    payload.writeTo(this)
    return this
}

internal fun BufferedSource.readUByte(): Int = readByte().toInt() and 0xFF

// --- async variants for streaming a network response (BufferedAsyncSource) ---

private suspend fun BufferedAsyncSource.readUByteA(): Int {
    require(1)
    return buffer.readByte().toInt() and 0xFF
}

suspend fun BufferedAsyncSource.readUmpVarInt(): Long {
    val prefix = readUByteA()
    return when {
        prefix < 0x80 -> prefix.toLong()
        prefix < 0xC0 -> (prefix and 0x3F) + 64L * readUByteA()
        prefix < 0xE0 -> (prefix and 0x1F) + 32L * (readUByteA() + 256L * readUByteA())
        prefix < 0xF0 -> (prefix and 0x0F) + 16L * (readUByteA() + 256L * (readUByteA() + 256L * readUByteA()))
        else -> readUByteA() + 256L * (readUByteA() + 256L * (readUByteA() + 256L * readUByteA()))
    }
}

suspend fun BufferedAsyncSource.readUmpPartHeader(): UmpPartHeader {
    val partId = readUmpVarInt().toInt()
    val size = readUmpVarInt()
    return UmpPartHeader(partId, size)
}

/** Read exactly [count] bytes into a fresh ByteArray (small control-part payloads). */
suspend fun BufferedAsyncSource.readBytesFully(count: Long): ByteArray {
    require(count)
    return buffer.readByteArray(count)
}

/** Skip exactly [count] bytes. */
suspend fun BufferedAsyncSource.skipFully(count: Long) {
    var remaining = count
    while (remaining > 0) {
        require(1)
        val chunk = minOf(remaining, buffer.size)
        buffer.skip(chunk)
        remaining -= chunk
    }
}

/** Copy exactly [count] bytes into [sink]. */
suspend fun BufferedAsyncSource.copyFully(sink: BufferedSink, count: Long) {
    var remaining = count
    while (remaining > 0) {
        require(1)
        val chunk = minOf(remaining, buffer.size)
        sink.write(buffer, chunk)
        remaining -= chunk
    }
    sink.flush()
}
