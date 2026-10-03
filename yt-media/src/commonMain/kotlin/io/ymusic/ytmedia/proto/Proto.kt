package io.ymusic.ytmedia.proto

import kmphttp.MediaType
import kmphttp.RequestBody
import kmphttp.RequestBodyX.asOneshotRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString
import okio.IOException

const val WIRE_TYPE_VARINT = 0
const val WIRE_TYPE_FIXED64 = 1
const val WIRE_TYPE_LEN = 2
const val WIRE_TYPE_FIXED32 = 5

sealed class ProtoField(val fieldNumber: Int, val wireType: Int)

class ProtoFieldVarInt(fieldNumber: Int, val asLong: Long) : ProtoField(fieldNumber, WIRE_TYPE_VARINT)

class ProtoFieldFixed64(fieldNumber: Int, val bytes: ByteArray) : ProtoField(fieldNumber, WIRE_TYPE_FIXED64) {
    val asLong: Long by lazy { Buffer().write(bytes).readLongLe() }
}

class ProtoFieldFixed32(fieldNumber: Int, val bytes: ByteArray) : ProtoField(fieldNumber, WIRE_TYPE_FIXED32) {
    val asInt: Int by lazy { Buffer().write(bytes).readIntLe() }
    val asFloat: Float by lazy { Float.fromBits(asInt) }
}

class ProtoFieldLen(fieldNumber: Int, val payload: ByteString) : ProtoField(fieldNumber, WIRE_TYPE_LEN) {
    val asString: String by lazy { payload.utf8() }
    val asMessage: List<ProtoField> by lazy { ProtoReader(payload).readAllFields() }
}

/**
 * Reads protobuf fields from [source], bounded to [remaining] bytes — the message's own byte length.
 * Passing an explicit length lets the message be parsed **in place** from a shared buffer (e.g. a
 * `BufferedAsyncSource.buffer` that still holds following parts): the reader stops at the boundary
 * and never reads into what follows. Any field that would read past [remaining] throws.
 */
class ProtoReader(private val source: BufferedSource, private var remaining: Long) {
    /** Parse a self-contained protobuf message — its own byte length bounds it. */
    constructor(bytes: ByteArray) : this(Buffer().write(bytes), bytes.size.toLong())
    constructor(payload: ByteString) : this(Buffer().write(payload), payload.size.toLong())

    fun hasMore(): Boolean = remaining > 0

    private fun readByte(): Byte {
        if (remaining < 1) throw IOException("protobuf field reads past the message end")
        remaining -= 1
        return source.readByte()
    }

    private fun readBytes(length: Long): ByteArray {
        if (length > remaining) throw IOException("protobuf field length $length exceeds remaining $remaining")
        remaining -= length
        return source.readByteArray(length)
    }

    private fun readByteString(length: Long): ByteString {
        if (length > remaining) throw IOException("protobuf field length $length exceeds remaining $remaining")
        remaining -= length
        return source.readByteString(length)
    }

    private fun readVarInt(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = readByte().toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift >= 64) throw IOException("VarInt too large")
        }
        return result
    }

    fun readNextField(): ProtoField {
        val tag = readVarInt()
        val fieldNumber = (tag shr 3).toInt()
        return when (val wireType = (tag and 7).toInt()) {
            WIRE_TYPE_VARINT -> ProtoFieldVarInt(fieldNumber, readVarInt())
            WIRE_TYPE_FIXED64 -> ProtoFieldFixed64(fieldNumber, readBytes(8))
            WIRE_TYPE_FIXED32 -> ProtoFieldFixed32(fieldNumber, readBytes(4))
            WIRE_TYPE_LEN -> ProtoFieldLen(fieldNumber, readByteString(readVarInt()))
            else -> throw IOException("Unsupported wire type: $wireType")
        }
    }

    fun readAllFields(): List<ProtoField> {
        val fields = mutableListOf<ProtoField>()
        while (hasMore()) {
            fields.add(readNextField())
        }
        return fields
    }
}

fun List<ProtoField>.varint(fieldNumber: Int): Long? =
    (find { it.fieldNumber == fieldNumber } as? ProtoFieldVarInt)?.asLong

fun List<ProtoField>.string(fieldNumber: Int): String? =
    (find { it.fieldNumber == fieldNumber } as? ProtoFieldLen)?.asString

fun List<ProtoField>.bytes(fieldNumber: Int): ByteArray? =
    (find { it.fieldNumber == fieldNumber } as? ProtoFieldLen)?.payload?.toByteArray()

fun List<ProtoField>.message(fieldNumber: Int): List<ProtoField>? =
    (find { it.fieldNumber == fieldNumber } as? ProtoFieldLen)?.asMessage

fun List<ProtoField>.messages(fieldNumber: Int): List<List<ProtoField>> =
    filter { it.fieldNumber == fieldNumber }.map { (it as ProtoFieldLen).asMessage }

/** Length-prefixed writes need the child's size first, so nested messages build in their own buffer. */
class ProtoWriter {
    val buffer = Buffer()

    val size: Long get() = buffer.size

    fun toRequestBody(contentType: MediaType? = null): RequestBody =
        buffer.asOneshotRequestBody(contentType, buffer.size)

    fun writeTo(sink: BufferedSink) {
        sink.writeAll(buffer)
    }

    private fun writeVarInt(value: Long) {
        var current = value
        while (current and 0x7FL.inv() != 0L) {
            buffer.writeByte(((current and 0x7F) or 0x80).toInt())
            current = current ushr 7
        }
        buffer.writeByte(current.toInt())
    }

    private fun tag(fieldNumber: Int, wireType: Int) {
        writeVarInt(((fieldNumber shl 3) or wireType).toLong())
    }

    fun varint(fieldNumber: Int, value: Long?) = apply {
        value ?: return@apply
        tag(fieldNumber, WIRE_TYPE_VARINT)
        writeVarInt(value)
    }

    fun varint(fieldNumber: Int, value: Int?) = varint(fieldNumber, value?.toLong())

    fun string(fieldNumber: Int, value: String?) = apply {
        value ?: return@apply
        bytes(fieldNumber, value.encodeToByteArray())
    }

    fun bytes(fieldNumber: Int, value: ByteArray?) = apply {
        value ?: return@apply
        tag(fieldNumber, WIRE_TYPE_LEN)
        writeVarInt(value.size.toLong())
        buffer.write(value)
    }

    fun message(fieldNumber: Int, value: ProtoWriter?) = apply {
        value ?: return@apply
        tag(fieldNumber, WIRE_TYPE_LEN)
        writeVarInt(value.size)
        value.writeTo(buffer)
    }

    fun messages(fieldNumber: Int, values: List<ProtoWriter>) = apply {
        values.forEach { message(fieldNumber, it) }
    }
}
