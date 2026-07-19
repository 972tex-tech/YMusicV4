package kmphttp.internal

import kmpcommon.io.AsyncSink
import kmpcommon.io.AsyncSource
import okio.Buffer
import okio.FileSystem
import okio.IOException
import okio.Path

/**
 * Close resource silently, ignoring exceptions.
 * Use sparingly - only when cleanup failure is acceptable.
 */
internal fun okio.Closeable.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
        // Intentionally ignored during cleanup
    }
}

internal fun AutoCloseable.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
        // Intentionally ignored during cleanup
    }
}

/**
 * Delete file if it exists.
 * @return true if deleted successfully.
 */
internal fun FileSystem.deleteIgnoreResult(path: Path) {
    try {
        delete(path)
    } catch (_: IOException) {
    }
}

open class ForwardingAsyncSink(
    private val delegate: AsyncSink,
) : AsyncSink {
    override fun close() {
        delegate.close()
    }

    override suspend fun flush() {
        delegate.flush()
    }

    override suspend fun write(buffer: Buffer, byteCount: Long) {
        delegate.write(buffer, byteCount)
    }
}
open class ForwardingAsyncSource(
    private val delegate: AsyncSource,
) : AsyncSource {
    override fun close() {
        delegate.close()
    }

    override suspend fun read(sink: Buffer, byteCount: Long): Long {
        return delegate.read(sink, byteCount)
    }
}

/** okio's ForwardingSink is JVM-only; this is the common equivalent. */
open class ForwardingOkioSink(private val delegate: okio.Sink) : okio.Sink {
    override fun write(source: Buffer, byteCount: Long) = delegate.write(source, byteCount)

    override fun flush() = delegate.flush()

    override fun timeout(): okio.Timeout = delegate.timeout()

    override fun close() = delegate.close()
}
