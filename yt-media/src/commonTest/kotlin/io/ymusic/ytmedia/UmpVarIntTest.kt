package io.ymusic.ytmedia

import io.ymusic.ytmedia.ump.readUmpPartHeader
import io.ymusic.ytmedia.ump.readUmpVarInt
import io.ymusic.ytmedia.ump.writeUmpPartHeader
import io.ymusic.ytmedia.ump.writeUmpVarInt
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UmpVarIntTest {

    @Test
    fun roundtrip_across_all_size_boundaries() {
        val values = listOf(
            0L, 1L, 20L, 127L, // 1 byte
            128L, 813L, 16383L, // 2 bytes
            16384L, (1L shl 21) - 1, // 3 bytes
            1L shl 21, (1L shl 28) - 1, // 4 bytes
            1L shl 28, 0xFFFF_FFFFL, // 5 bytes
        )
        for (value in values) {
            val buffer = Buffer().writeUmpVarInt(value)
            assertEquals(value, buffer.readUmpVarInt(), "value $value")
            assertEquals(0, buffer.size, "value $value left bytes behind")
        }
    }

    @Test
    fun encoded_sizes_match_the_prefix_scheme() {
        assertEquals(1, Buffer().writeUmpVarInt(127).size)
        assertEquals(2, Buffer().writeUmpVarInt(128).size)
        assertEquals(2, Buffer().writeUmpVarInt(16383).size)
        assertEquals(3, Buffer().writeUmpVarInt(16384).size)
        assertEquals(4, Buffer().writeUmpVarInt(1L shl 21).size)
        assertEquals(5, Buffer().writeUmpVarInt(1L shl 28).size)
    }

    // Pinned wire bytes — this is googlevideo's format, it must not drift with refactors.
    @Test
    fun pinned_byte_patterns() {
        assertContentEquals(byteArrayOf(20), Buffer().writeUmpVarInt(20).readByteArray())
        assertContentEquals(
            byteArrayOf(0x80.toByte(), 0x02),
            Buffer().writeUmpVarInt(128).readByteArray(),
        )
        assertContentEquals(
            byteArrayOf(0xAD.toByte(), 0x0C),
            Buffer().writeUmpVarInt(813).readByteArray(),
        )
        assertContentEquals(
            byteArrayOf(0xF0.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            Buffer().writeUmpVarInt(0xFFFF_FFFFL).readByteArray(),
        )
    }

    @Test
    fun part_header_roundtrip() {
        val buffer = Buffer().writeUmpPartHeader(21, 1_048_577L)
        val header = buffer.readUmpPartHeader()
        assertEquals(21, header.partId)
        assertEquals(1_048_577L, header.size)
        assertEquals(0, buffer.size)
    }

    @Test
    fun rejects_values_out_of_range() {
        assertFailsWith<IllegalArgumentException> { Buffer().writeUmpVarInt(-1) }
        assertFailsWith<IllegalArgumentException> { Buffer().writeUmpVarInt(1L shl 32) }
    }
}
