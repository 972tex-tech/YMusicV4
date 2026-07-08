package kmpffmpeg

import android.content.ContentResolver
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Instrumented tests for [FfYmixProtocolServer]: a [LocalSocket] client speaks the `ymix` wire protocol against
 * the server (no ffmpeg — see [FfmpegRealBinaryTest] for the real-binary path), checking it serves and
 * receives the right bytes. The server opens its file via [ContentResolver.openFileDescriptor]; we back
 * it with a `file://` uri ([Uri.fromFile]), which the resolver opens natively.
 */
class FfYmixProtocolServerTest {

    private val contentResolver: ContentResolver
        get() = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

    private val cacheDir: File
        get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @Test
    fun readServesFileInChunksThenEof() {
        val bytes = ByteArray(70_000) { (it * 31).toByte() } // > 2 × the 32 KiB chunk cap
        val server = serve(tempFile("read", bytes))
        val sock = connect(server)
        try {
            assertEquals("ok", reply(sock, "start_session read"))
            assertEquals(bytes.size.toLong(), stat(sock))
            // Pull the whole file: each `read` returns ≤ 32 KiB; pos 0 then advancing, until `chunk 0`.
            val got = readAllViaChunks(sock, from = 0)
            assertArrayEquals(bytes, got)
            assertEquals("ok", reply(sock, "bye"))
        } finally {
            sock.close(); server.close()
        }
    }

    @Test
    fun readChunkIsCappedAt32KiB() {
        val server = serve(tempFile("cap", ByteArray(100_000) { it.toByte() }))
        val sock = connect(server)
        try {
            assertEquals("ok", reply(sock, "start_session read"))
            send(sock, "read 0 99999")            // ask for way more than the cap
            val n = chunkHeader(sock.inputStream)
            assertEquals("server must cap a read at 32 KiB", 32 * 1024, n)
            readExact(sock.inputStream, n)
        } finally {
            sock.close(); server.close()
        }
    }

    @Test
    fun readFromOffsetThenEofAtEnd() {
        val bytes = ByteArray(10_000) { it.toByte() }
        val server = serve(tempFile("off", bytes))
        val sock = connect(server)
        try {
            assertEquals("ok", reply(sock, "start_session read"))
            assertArrayEquals(bytes.copyOfRange(4000, bytes.size), readAllViaChunks(sock, from = 4000))
            // A read at EOF yields a zero-length chunk (not a socket close).
            send(sock, "read ${bytes.size} 1024")
            assertEquals(0, chunkHeader(sock.inputStream))
        } finally {
            sock.close(); server.close()
        }
    }

    @Test
    fun writeReceivesBytesAtOffsetAndTruncatesAtOpen() {
        val file = tempFile("write", ByteArray(5000) { 0xFF.toByte() }) // pre-existing content
        val server = serve(file)
        val sock = connect(server)
        try {
            assertEquals("ok", reply(sock, "start_session write")) // "rwt" truncates here
            val head = ByteArray(20_000) { (it * 7 + 3).toByte() }
            val tail = ByteArray(4_000) { (it * 13 + 1).toByte() }
            assertEquals("ok", write(sock, pos = 0, head))
            assertEquals("ok", write(sock, pos = 20_000, tail))
            assertEquals((head.size + tail.size).toLong(), stat(sock))
            assertEquals("ok", reply(sock, "bye")) // flush + fsync barrier
        } finally {
            sock.close(); server.close()
        }
        assertArrayEquals(ByteArray(20_000) { (it * 7 + 3).toByte() } + ByteArray(4_000) { (it * 13 + 1).toByte() },
            file.readBytes())
    }

    /**
     * The mp4 `+faststart` pattern: a write connection writes the output while a SECOND, independent read
     * connection on the same socket reads it back (to relocate the moov atom). Each connection opens its
     * own descriptor; the read must see bytes the write already committed. No session ids, no cross-wire.
     */
    @Test
    fun faststartReadConnectionSeesConcurrentWrite() {
        val file = tempFile("faststart", ByteArray(0))
        val server = serve(file)
        val payload = ByteArray(50_000) { (it * 17 + 5).toByte() }
        val writeSock = connect(server)
        val readSock = connect(server)
        try {
            // write connection commits the payload at 0
            assertEquals("ok", reply(writeSock, "start_session write"))
            assertEquals("ok", write(writeSock, pos = 0, payload))

            // a separate read connection (write conn still open) reads it back from an offset of its own
            assertEquals("ok", reply(readSock, "start_session read"))
            assertEquals(payload.size.toLong(), stat(readSock))
            assertArrayEquals(payload.copyOfRange(8000, payload.size), readAllViaChunks(readSock, from = 8000))

            assertEquals("ok", reply(readSock, "bye"))
            assertEquals("ok", reply(writeSock, "bye"))
        } finally {
            readSock.close(); writeSock.close(); server.close()
        }
    }

    @Test
    fun badHandshakeIsReportedAsErrorCode() {
        val server = serve(tempFile("bad", ByteArray(10)))
        val sock = connect(server)
        try {
            send(sock, "hello there")
            assertEquals("error bad_handshake", reply0(sock))
        } finally {
            sock.close(); server.close()
        }
    }

    @Test
    fun unknownCommandIsReportedAsErrorCode() {
        val server = serve(tempFile("unk", ByteArray(10)))
        val sock = connect(server)
        try {
            assertEquals("ok", reply(sock, "start_session read"))
            assertEquals("error unknown_command", reply(sock, "frobnicate 1 2"))
        } finally {
            sock.close(); server.close()
        }
    }

    @Test
    fun openFailureIsClassifiedAsErrorCode() {
        // A read server backed by a file that doesn't exist: the handshake's openFileDescriptor throws
        // FileNotFoundException, which the server classifies and reports as a stable code.
        val missing = File(cacheDir, "ymix-missing-${System.nanoTime()}.bin") // never created
        val server = FfYmixProtocolServer(contentResolver, Uri.fromFile(missing)).also { it.start() }
        val sock = connect(server)
        try {
            assertEquals("error file_not_found", reply(sock, "start_session read"))
        } finally {
            sock.close(); server.close()
        }
    }

    // ---- helpers ----

    // No explicit socketName: the server's default name is process-unique, so tests never collide on the
    // abstract namespace. The client derives the name from server.url, so its exact value doesn't matter.
    private fun serve(file: File): FfYmixProtocolServer =
        FfYmixProtocolServer(contentResolver, Uri.fromFile(file)).also { it.start() }

    private fun connect(server: FfYmixProtocolServer): LocalSocket = LocalSocket().apply {
        connect(LocalSocketAddress(server.url.removePrefix("ymix:0"), LocalSocketAddress.Namespace.ABSTRACT))
    }

    private fun send(sock: LocalSocket, line: String) {
        sock.outputStream.write("$line\n".toByteArray()); sock.outputStream.flush()
    }

    /** Send a command, return its single-line reply. */
    private fun reply(sock: LocalSocket, cmd: String): String { send(sock, cmd); return readLine(sock.inputStream) }
    private fun reply0(sock: LocalSocket): String = readLine(sock.inputStream)

    private fun stat(sock: LocalSocket): Long {
        val parts = reply(sock, "stat").split(' ')
        assertEquals("stat_ok", parts[0])
        return parts[1].toLong()
    }

    private fun write(sock: LocalSocket, pos: Int, data: ByteArray): String {
        send(sock, "write $pos ${data.size}")
        sock.outputStream.write(data); sock.outputStream.flush()
        return readLine(sock.inputStream)
    }

    /** Read the `chunk <n>` header line, return n. */
    private fun chunkHeader(input: InputStream): Int {
        val parts = readLine(input).split(' ')
        assertEquals("chunk", parts[0])
        return parts[1].toInt()
    }

    /** Drive `read <pos> <len>` from [from] until a zero-length chunk, returning the concatenated bytes. */
    private fun readAllViaChunks(sock: LocalSocket, from: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var pos = from
        while (true) {
            send(sock, "read $pos 32768")
            val n = chunkHeader(sock.inputStream)
            if (n == 0) break
            out.write(readExact(sock.inputStream, n)); pos += n
        }
        return out.toByteArray()
    }

    private fun readExact(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n); var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) error("unexpected EOF after $off/$n bytes")
            off += r
        }
        return buf
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun tempFile(prefix: String, content: ByteArray): File =
        File.createTempFile("ymix-$prefix-", ".bin", cacheDir).apply { writeBytes(content); deleteOnExit() }
}
