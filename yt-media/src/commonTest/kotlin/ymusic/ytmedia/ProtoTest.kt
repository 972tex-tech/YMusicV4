package ymusic.ytmedia

import okio.Buffer
import ymusic.ytmedia.proto.ProtoFieldFixed32
import ymusic.ytmedia.proto.ProtoFieldFixed64
import ymusic.ytmedia.proto.ProtoReader
import ymusic.ytmedia.proto.ProtoWriter
import ymusic.ytmedia.proto.bytes
import ymusic.ytmedia.proto.message
import ymusic.ytmedia.proto.messages
import ymusic.ytmedia.proto.string
import ymusic.ytmedia.proto.varint
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtoTest {

    @Test
    fun writer_reader_roundtrip() {
        val nested = ProtoWriter().varint(1, 42).string(2, "abc")
        val encoded = ProtoWriter()
            .varint(1, Long.MAX_VALUE)
            .varint(2, 0)
            .string(3, "hello プロト")
            .bytes(4, byteArrayOf(0, -1, 127))
            .message(5, nested)
            .messages(6, listOf(ProtoWriter().varint(1, 1), ProtoWriter().varint(1, 2)))
            .toByteArray()

        val fields = ProtoReader(Buffer().write(encoded)).readAllFields()
        assertEquals(Long.MAX_VALUE, fields.varint(1))
        assertEquals(0L, fields.varint(2))
        assertEquals("hello プロト", fields.string(3))
        assertContentEquals(byteArrayOf(0, -1, 127), fields.bytes(4))
        val nestedFields = fields.message(5)!!
        assertEquals(42L, nestedFields.varint(1))
        assertEquals("abc", nestedFields.string(2))
        assertEquals(listOf(1L, 2L), fields.messages(6).map { it.varint(1) })
        assertNull(fields.varint(99))
    }

    @Test
    fun null_values_write_nothing() {
        val writer = ProtoWriter()
            .varint(1, null as Long?)
            .string(2, null)
            .bytes(3, null)
            .message(4, null)
        assertEquals(0, writer.size)
    }

    @Test
    fun byte_limit_parses_one_message_in_place_leaving_the_rest() {
        // Two length-delimited messages concatenated in one buffer (like consecutive UMP parts).
        val first = ProtoWriter().varint(1, 7).string(2, "a")
        val second = ProtoWriter().varint(1, 99)
        val buffer = Buffer().write(first.toByteArray()).write(second.toByteArray())
        val firstSize = first.size

        // Bounded reader consumes exactly the first message and stops, though more bytes follow.
        val fields = ProtoReader(buffer, firstSize).readAllFields()
        assertEquals(7L, fields.varint(1))
        assertEquals("a", fields.string(2))
        assertEquals(second.size, buffer.size) // the second message is untouched

        // The buffer is positioned to read the second message next.
        assertEquals(99L, ProtoReader(buffer).readAllFields().varint(1))
    }

    // The writer never emits fixed32/64, but responses may contain them — decode little-endian.
    @Test
    fun reader_decodes_fixed_width_fields() {
        val bytes = Buffer()
            .writeByte((1 shl 3) or 1) // field 1, fixed64
            .writeLongLe(-2L)
            .writeByte((2 shl 3) or 5) // field 2, fixed32
            .writeIntLe(1_234_567)
            .readByteArray()
        val fields = ProtoReader(Buffer().write(bytes)).readAllFields()
        assertEquals(-2L, (fields[0] as ProtoFieldFixed64).asLong)
        assertEquals(1_234_567, (fields[1] as ProtoFieldFixed32).asInt)
    }
}
