package kmpcommon.io

import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.EOFException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BufferedAsyncSourceTest {

    /**
     * Creates a test AsyncSource from ByteArray that returns data in chunks
     */
    private fun ByteArray.toChunkedAsyncSource(chunkSize: Int): AsyncSource {
        var offset = 0
        return object : AsyncSource {
            override suspend fun read(sink: Buffer, byteCount: Long): Long {
                if (offset >= size) return -1L
                val toRead = minOf(chunkSize.toLong(), byteCount, (size - offset).toLong()).toInt()
                sink.write(this@toChunkedAsyncSource, offset, toRead)
                offset += toRead
                return toRead.toLong()
            }

            override fun close() = Unit
        }
    }

    // region read

    @Test
    fun testReadFromBuffer() = runTest {
        val data = "Hello, World!".encodeToByteArray()
        val source = BufferedAsyncSource(data.toChunkedAsyncSource(5))

        // Pre-fill buffer
        source.request(5)
        assertEquals(5L, source.buffer.size)

        // Read from buffer
        val sink = Buffer()
        val bytesRead = source.read(sink, 3)

        assertEquals(3L, bytesRead)
        assertEquals("Hel", sink.readUtf8())
        assertEquals(2L, source.buffer.size) // Remaining in buffer
    }

    @Test
    fun testReadDelegatesToSource() = runTest {
        val data = "Hello, World!".encodeToByteArray()
        val source = BufferedAsyncSource(data.toChunkedAsyncSource(100))

        // Buffer is empty, should delegate
        val sink = Buffer()
        val bytesRead = source.read(sink, 5)

        assertEquals(5L, bytesRead)
        assertEquals("Hello", sink.readUtf8())
    }

    @Test
    fun testReadReturnsMinOfBufferAndRequested() = runTest {
        val data = "Hello".encodeToByteArray()
        val source = BufferedAsyncSource(data.toChunkedAsyncSource(100))

        source.request(5)

        val sink = Buffer()
        val bytesRead = source.read(sink, 100) // Request more than available

        assertEquals(5L, bytesRead)
        assertEquals("Hello", sink.readUtf8())
    }

    // endregion

    // region request

    @Test
    fun testRequestExactBytes() = runTest {
        val data = "Hello, World!".encodeToByteArray()
        val source = BufferedAsyncSource(data.toChunkedAsyncSource(5))

        val result = source.request(10)

        assertTrue(result)
        assertTrue(source.buffer.size >= 10)
    }

    @Test
    fun testRequestMoreThanAvailable() = runTest {
        val data = "Hello".encodeToByteArray()
        val source = BufferedAsyncSource(data.toChunkedAsyncSource(100))

        val result = source.request(100)

        assertFalse(result)
        assertEquals(5L, source.buffer.size) // All available data loaded
    }

    @Test
    fun testRequestZeroBytes() = runTest {
        val source = BufferedAsyncSource(AsyncSource.EMPTY)

        val result = source.request(0)

        assertTrue(result)
    }

    @Test
    fun testRequestWhenAlreadySatisfied() = runTest {
        val data = "Hello, World!".encodeToByteArray()
        val source = BufferedAsyncSource(data.toChunkedAsyncSource(100))

        source.request(5)
        val initialSize = source.buffer.size

        val result = source.request(3) // Less than already buffered

        assertTrue(result)
        assertEquals(initialSize, source.buffer.size) // No additional read
    }

    // endregion

    // region readFully

    @Test
    fun testReadFully() = runTest {
        val data = "Hello, World!".encodeToByteArray()
        val source = BufferedAsyncSource(data.toChunkedAsyncSource(5))

        val buffer = source.readFully()

        assertEquals("Hello, World!", buffer.readUtf8())
    }

    @Test
    fun testReadFullyEmpty() = runTest {
        val source = BufferedAsyncSource(AsyncSource.EMPTY)

        val buffer = source.readFully()

        assertEquals(0L, buffer.size)
    }

    @Test
    fun testReadFullyLargeData() = runTest {
        val data = ByteArray(100_000) { it.toByte() }
        val source = BufferedAsyncSource(data.toChunkedAsyncSource(1024))

        val buffer = source.readFully()

        assertEquals(100_000L, buffer.size)
        assertTrue(data.contentEquals(buffer.readByteArray()))
    }

    // endregion

    // region indexOf

    @Test
    fun testIndexOfFound() = runTest {
        val data = "Hello, World!".encodeToByteArray()
        val source = BufferedAsyncSource(data.toChunkedAsyncSource(5))

        val index = source.indexOf(','.code.toByte(), 0)

        assertEquals(5L, index)
    }

    @Test
    fun testIndexOfNotFound() = runTest {
        val data = "Hello World".encodeToByteArray()
        val source = BufferedAsyncSource(data.toChunkedAsyncSource(5))

        val index = source.indexOf(','.code.toByte(), 0)

        assertEquals(-1L, index)
    }

    @Test
    fun testIndexOfWithFromIndex() = runTest {
        val data = "a,b,c,d".encodeToByteArray()
        val source = BufferedAsyncSource(data.toChunkedAsyncSource(100))

        val firstComma = source.indexOf(','.code.toByte(), 0)
        val secondComma = source.indexOf(','.code.toByte(), firstComma + 1)
        val thirdComma = source.indexOf(','.code.toByte(), secondComma + 1)

        assertEquals(1L, firstComma)
        assertEquals(3L, secondComma)
        assertEquals(5L, thirdComma)
    }

    @Test
    fun testIndexOfWithToIndex() = runTest {
        val data = "Hello, World!".encodeToByteArray()
        val source = BufferedAsyncSource(data.toChunkedAsyncSource(100))

        // Comma at index 5, limit search to index 5 (exclusive)
        val index = source.indexOf(','.code.toByte(), 0, 5)

        assertEquals(-1L, index) // Not found within range
    }

    @Test
    fun testIndexOfWithToIndexIncludesTarget() = runTest {
        val data = "Hello, World!".encodeToByteArray()
        val source = BufferedAsyncSource(data.toChunkedAsyncSource(100))

        // Comma at index 5, limit search to index 6 (exclusive)
        val index = source.indexOf(','.code.toByte(), 0, 6)

        assertEquals(5L, index)
    }

    @Test
    fun testIndexOfEqualFromAndTo() = runTest {
        val data = "Hello".encodeToByteArray()
        val source = BufferedAsyncSource(data.toChunkedAsyncSource(100))

        val index = source.indexOf('H'.code.toByte(), 0, 0)

        assertEquals(-1L, index) // Empty range
    }

    @Test
    fun testIndexOfBeyondData() = runTest {
        val data = "Hello".encodeToByteArray()
        val source = BufferedAsyncSource(data.toChunkedAsyncSource(100))

        val index = source.indexOf('x'.code.toByte(), 0, 1000)

        assertEquals(-1L, index)
    }

    @Test
    fun testIndexOfAtEndOfData() = runTest {
        val data = "Hello!".encodeToByteArray()
        val source = BufferedAsyncSource(data.toChunkedAsyncSource(3))

        val index = source.indexOf('!'.code.toByte(), 0)

        assertEquals(5L, index)
    }

    @Test
    fun testIndexOfRequiresBufferExpansion() = runTest {
        // Create data where target byte 'X' is at position 50
        // Use small chunk size (10) so buffer must expand multiple times
        val data = ByteArray(100) { i -> if (i == 50) 'X'.code.toByte() else 'a'.code.toByte() }
        var readCount = 0
        val source = BufferedAsyncSource(object : AsyncSource {
            var offset = 0
            override suspend fun read(sink: Buffer, byteCount: Long): Long {
                if (offset >= data.size) return -1L
                val toRead = minOf(10, byteCount.toInt(), data.size - offset)
                sink.write(data, offset, toRead)
                offset += toRead
                readCount++
                return toRead.toLong()
            }
            override fun close() = Unit
        })

        val index = source.indexOf('X'.code.toByte(), 0)

        assertEquals(50L, index)
        assertTrue(readCount > 1, "Buffer should have expanded (readCount=$readCount)")
        assertTrue(source.buffer.size >= 51, "Buffer should contain at least 51 bytes")
    }

    @Test
    fun testIndexOfExpandsBufferIncrementally() = runTest {
        // Target at position 25, chunk size 8
        // Buffer expansion: 8 -> 16 -> 24 -> 32 (finds target)
        val data = ByteArray(50) { i -> if (i == 25) 'Z'.code.toByte() else '.'.code.toByte() }
        val source = BufferedAsyncSource(data.toChunkedAsyncSource(8))

        // Initially buffer is empty
        assertEquals(0L, source.buffer.size)

        val index = source.indexOf('Z'.code.toByte(), 0)

        assertEquals(25L, index)
        // Buffer should have grown to find the target
        assertTrue(source.buffer.size >= 26, "Buffer size=${source.buffer.size}")
    }

    @Test
    fun testIndexOfWithToIndexCausesPartialExpansion() = runTest {
        // Data: 100 bytes, target 'Y' at position 80
        // Search with toIndex=60, should NOT find it (no need to expand beyond 60)
        val data = ByteArray(100) { i -> if (i == 80) 'Y'.code.toByte() else '-'.code.toByte() }
        val source = BufferedAsyncSource(data.toChunkedAsyncSource(20))

        val index = source.indexOf('Y'.code.toByte(), 0, 60)

        assertEquals(-1L, index)
        // Buffer should not have loaded more than ~60 bytes
        assertTrue(source.buffer.size <= 80, "Buffer should not over-expand: ${source.buffer.size}")
    }

    @Test
    fun testIndexOfExpandsFromMiddleOfBuffer() = runTest {
        // Pre-fill buffer with some data, then search from middle requiring expansion
        val data = "0123456789ABCDEFGHIJ*KLMNOP".encodeToByteArray() // '*' at position 20
        val source = BufferedAsyncSource(data.toChunkedAsyncSource(5))

        // Pre-fill 10 bytes
        source.request(10)
        assertEquals(10L, source.buffer.size)

        // Search from position 15 - target '*' at position 20
        val index = source.indexOf('*'.code.toByte(), 15)

        assertEquals(20L, index)
        assertTrue(source.buffer.size >= 21, "Buffer should expand to find target")
    }

    // endregion

    // region close

    @Test
    fun testCloseCallsDelegate() = runTest {
        var closed = false
        val delegate = object : AsyncSource {
            override suspend fun read(sink: Buffer, byteCount: Long) = -1L
            override fun close() {
                closed = true
            }
        }

        val source = BufferedAsyncSource(delegate)
        source.close()

        assertTrue(closed)
    }

    // endregion

    // region extension functions

    @Test
    fun testReadUtf8String() = runTest {
        val data = "Hello, World!".encodeToByteArray()
        val source = data.toChunkedAsyncSource(5)

        val result = source.readUtf8String()

        assertEquals("Hello, World!", result)
    }

    @Test
    fun testReadByteArray() = runTest {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val source = data.toChunkedAsyncSource(2)

        val result = source.readByteArray()

        assertTrue(data.contentEquals(result))
    }

    // endregion

    // region fixed-width integers

    @Test
    fun testReadIntAndLongAcrossChunks() = runTest {
        // big-endian int then long, delivered one byte per read so the reads span chunks
        val data = Buffer().apply { writeInt(-42); writeLong(1234567890123L) }.readByteArray()
        val source = BufferedAsyncSource(data.toChunkedAsyncSource(1))

        assertEquals(-42, source.readInt())
        assertEquals(1234567890123L, source.readLong())
    }

    @Test
    fun testReadLongThrowsOnShortStream() = runTest {
        val source = BufferedAsyncSource(byteArrayOf(1, 2, 3).toChunkedAsyncSource(1))

        assertFailsWith<EOFException> { source.readLong() }
    }

    // endregion
}
