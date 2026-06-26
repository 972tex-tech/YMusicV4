package kmphttp.internal

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import okio.Buffer
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcmp
import platform.posix.uint8_tVar
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@OptIn(ExperimentalForeignApi::class)
class OkioXTest {

    @Test
    fun copyEmptyBuffer() = memScoped {
        val buffer = Buffer()
        val dest = allocArray<uint8_tVar>(10)

        val copied = buffer.read(dest, 10)

        assertEquals(0L, copied)
        assertEquals(0L, buffer.size)
    }

    @Test
    fun copyEntireBuffer() = memScoped {
        val data = "Hello, World!".encodeToByteArray()
        val buffer = Buffer().apply { write(data) }
        val dest = allocArray<uint8_tVar>(data.size)

        val copied = buffer.read(dest, data.size.toLong())

        assertEquals(data.size.toLong(), copied)
        assertEquals(0L, buffer.size, "Buffer should be consumed")
        data.usePinned { pinned ->
            assertEquals(0, memcmp(dest, pinned.addressOf(0), data.size.toULong()))
        }
    }

    @Test
    fun copyPartialBuffer() = memScoped {
        val data = "Hello, World!".encodeToByteArray()
        val buffer = Buffer().apply { write(data) }
        val dest = allocArray<uint8_tVar>(5)

        val copied = buffer.read(dest, 5)

        assertEquals(5L, copied)
        assertEquals((data.size - 5).toLong(), buffer.size, "Remaining bytes in buffer")
        "Hello".encodeToByteArray().usePinned { pinned ->
            assertEquals(0, memcmp(dest, pinned.addressOf(0), 5u))
        }
    }

    @Test
    fun copyWithByteCountLargerThanBuffer() = memScoped {
        val data = "Short".encodeToByteArray()
        val buffer = Buffer().apply { write(data) }
        val dest = allocArray<uint8_tVar>(100)

        val copied = buffer.read(dest, 100)

        assertEquals(data.size.toLong(), copied)
        assertEquals(0L, buffer.size)
    }

    @Test
    fun copyLargeBufferWithMultipleSegments() = memScoped {
        // Create buffer larger than segment size (typically 8KB) to test multi-segment copy
        val segmentSize = 8 * 1024
        val data = ByteArray(segmentSize * 3) { (it % 256).toByte() }
        val buffer = Buffer().apply { write(data) }
        val dest = allocArray<uint8_tVar>(data.size)

        val copied = buffer.read(dest, data.size.toLong())

        assertEquals(data.size.toLong(), copied)
        assertEquals(0L, buffer.size)
        data.usePinned { pinned ->
            assertEquals(0, memcmp(dest, pinned.addressOf(0), data.size.toULong()))
        }
    }

    @Test
    fun copyZeroBytes() = memScoped {
        val buffer = Buffer().apply { writeUtf8("data") }
        val dest = allocArray<uint8_tVar>(10)

        val copied = buffer.read(dest, 0)

        assertEquals(0L, copied)
        assertEquals(4L, buffer.size, "Buffer should not be consumed")
    }

    // ==================== Buffer.write(NSData) tests ====================

    @OptIn(BetaInteropApi::class)
    @Test
    fun writeEmptyNSData() {
        val buffer = Buffer()
        val nsData = NSData()

        val written = buffer.write(nsData)

        assertEquals(0L, written)
        assertEquals(0L, buffer.size)
    }

    @OptIn(BetaInteropApi::class)
    @Test
    fun writeNSDataToBuffer() {
        val data = "Hello, World!".encodeToByteArray()
        val nsData = data.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = data.size.toULong())
        }
        val buffer = Buffer()

        val written = buffer.write(nsData)

        assertEquals(data.size.toLong(), written)
        assertEquals(data.size.toLong(), buffer.size)
        assertEquals("Hello, World!", buffer.readUtf8())
    }

    @OptIn(BetaInteropApi::class)
    @Test
    fun writeNSDataToNonEmptyBuffer() {
        val existing = "Prefix: ".encodeToByteArray()
        val newData = "Hello".encodeToByteArray()
        val nsData = newData.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = newData.size.toULong())
        }
        val buffer = Buffer().apply { write(existing) }

        val written = buffer.write(nsData)

        assertEquals(newData.size.toLong(), written)
        assertEquals((existing.size + newData.size).toLong(), buffer.size)
        assertEquals("Prefix: Hello", buffer.readUtf8())
    }

    @OptIn(BetaInteropApi::class)
    @Test
    fun writeLargeNSDataWithMultipleSegments() {
        // Create data larger than segment size (8KB) to test multi-segment write
        val segmentSize = 8 * 1024
        val data = ByteArray(segmentSize * 3) { (it % 256).toByte() }
        val nsData = data.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = data.size.toULong())
        }
        val buffer = Buffer()

        val written = buffer.write(nsData)

        assertEquals(data.size.toLong(), written)
        assertEquals(data.size.toLong(), buffer.size)

        // Verify content
        val result = buffer.readByteArray()
        assertEquals(data.size, result.size)
        assertContentEquals(data, result)
    }
}
