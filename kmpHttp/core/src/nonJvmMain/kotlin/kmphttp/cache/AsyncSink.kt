package kmphttp.cache

import okio.Buffer
import okio.Closeable
import okio.FileSystem
import okio.Path

/**
 * This class is intended to wrap nodejs fs async write.
 */
interface AsyncSink : Closeable {
    /**
     * This function may perform either blocking or non-blocking write, so the caller should not be
     * main thread.
     */
    suspend fun write(buffer: Buffer, byteCount: Long)
    suspend fun flush()

    companion object {
        val BLACK_HOLE = object : AsyncSink {
            override suspend fun write(buffer: Buffer, byteCount: Long) = buffer.skip(byteCount)

            override suspend fun flush() = Unit

            override fun close() = Unit
        }
    }
}

