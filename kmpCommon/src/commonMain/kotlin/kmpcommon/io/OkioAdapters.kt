package kmpcommon.io

import okio.Buffer
import okio.Sink
import okio.Source

/** [AsyncSource] over a blocking okio [Source] — reads complete synchronously, never park. */
fun Source.asAsyncSource(): AsyncSource = object : AsyncSource {
    override suspend fun read(sink: Buffer, byteCount: Long): Long = this@asAsyncSource.read(sink, byteCount)

    override fun close() = this@asAsyncSource.close()
}

/** [AsyncSink] over a blocking okio [Sink] — writes complete synchronously, never park. */
fun Sink.asAsyncSink(): AsyncSink = object : AsyncSink {
    override suspend fun write(buffer: Buffer, byteCount: Long) = this@asAsyncSink.write(buffer, byteCount)

    override suspend fun flush() = this@asAsyncSink.flush()

    override fun close() = this@asAsyncSink.close()
}
