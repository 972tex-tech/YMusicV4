package kmphttp.internal

import kmpcommon.io.AsyncSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okio.Buffer
import platform.Foundation.*
import platform.darwin.*
import platform.posix.memcpy
import platform.posix.uint8_tVar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for AsyncSourceInputStream.
 */
@OptIn(ExperimentalForeignApi::class)
class AsyncSourceInputStreamTest {

    /**
     * Spin the run loop until the deferred completes.
     */
    private suspend fun <T> awaitWithRunLoop(deferred: Deferred<T>): T = coroutineScope {
        val runLoopJob = launch {
            while (!deferred.isCompleted) {
                val limitDate = NSDate.dateWithTimeIntervalSinceNow(0.01)
                NSRunLoop.mainRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = limitDate)
                delay(1.milliseconds)
            }
        }
        try {
            deferred.await()
        } finally {
            runLoopJob.cancel()
        }
    }

    @Test
    fun delegateReceivesOpenEvent() = runTest {
        val source = ByteArrayAsyncSource("test".encodeToByteArray())
        val stream = AsyncSourceInputStream(source) {}
        val completion = CompletableDeferred<Unit>()

        stream.delegate = object : NSObject(), NSStreamDelegateProtocol {
            override fun stream(aStream: NSStream, handleEvent: NSStreamEvent) {
                if (handleEvent == NSStreamEventOpenCompleted) {
                    completion.complete(Unit)
                }
            }
        }
        stream.scheduleInRunLoop(NSRunLoop.mainRunLoop, NSDefaultRunLoopMode)
        stream.open()

        withTimeout(5.seconds) { awaitWithRunLoop(completion) }
        stream.close()
    }

    @Test
    fun delegateReceivesBytesAvailableEvent() = runTest {
        val data = "Hello, World!".encodeToByteArray()
        val source = ByteArrayAsyncSource(data)
        val stream = AsyncSourceInputStream(source) {}
        val completion = CompletableDeferred<Unit>()

        stream.delegate = object : NSObject(), NSStreamDelegateProtocol {
            override fun stream(aStream: NSStream, handleEvent: NSStreamEvent) {
                if (handleEvent == NSStreamEventHasBytesAvailable) {
                    completion.complete(Unit)
                }
            }
        }
        stream.scheduleInRunLoop(NSRunLoop.mainRunLoop, NSDefaultRunLoopMode)
        stream.open()

        withTimeout(5.seconds) { awaitWithRunLoop(completion) }
        stream.close()
    }

    @Test
    fun delegateReceivesEndEvent() = runTest {
        val source = ByteArrayAsyncSource(ByteArray(0))
        val stream = AsyncSourceInputStream(source) {}
        val completion = CompletableDeferred<Unit>()

        stream.delegate = object : NSObject(), NSStreamDelegateProtocol {
            override fun stream(aStream: NSStream, handleEvent: NSStreamEvent) {
                if (handleEvent == NSStreamEventEndEncountered) {
                    completion.complete(Unit)
                }
            }
        }
        stream.scheduleInRunLoop(NSRunLoop.mainRunLoop, NSDefaultRunLoopMode)
        stream.open()

        withTimeout(5.seconds) { awaitWithRunLoop(completion) }
        stream.close()
    }

    @Test
    fun delegateReceivesErrorEvent() = runTest {
        val source = FailingAsyncSource(RuntimeException("Test error"))
        val stream = AsyncSourceInputStream(source) {}
        val completion = CompletableDeferred<Unit>()

        stream.delegate = object : NSObject(), NSStreamDelegateProtocol {
            override fun stream(aStream: NSStream, handleEvent: NSStreamEvent) {
                if (handleEvent == NSStreamEventErrorOccurred) {
                    completion.complete(Unit)
                }
            }
        }
        stream.scheduleInRunLoop(NSRunLoop.mainRunLoop, NSDefaultRunLoopMode)
        stream.open()

        withTimeout(5.seconds) { awaitWithRunLoop(completion) }
        stream.close()
    }

    @Test
    fun readDataWithDelegate() = runTest {
        val data = "Hello, World!".encodeToByteArray()
        val source = ByteArrayAsyncSource(data)
        val stream = AsyncSourceInputStream(source) {}
        val completion = CompletableDeferred<ByteArray>()

        stream.delegate = object : NSObject(), NSStreamDelegateProtocol {
            override fun stream(aStream: NSStream, handleEvent: NSStreamEvent) {
                when (handleEvent) {
                    NSStreamEventHasBytesAvailable -> {
                        memScoped {
                            val buffer = allocArray<uint8_tVar>(data.size)
                            val result = ByteArray(data.size)
                            var totalRead = 0
                            while (totalRead < data.size) {
                                val bytesRead = stream.read(buffer, (data.size - totalRead).toULong())
                                if (bytesRead <= 0) break
                                result.usePinned { pinned ->
                                    memcpy(pinned.addressOf(totalRead), buffer, bytesRead.toULong())
                                }
                                totalRead += bytesRead.toInt()
                            }
                            if (totalRead == data.size) {
                                completion.complete(result)
                            }
                        }
                    }
                    NSStreamEventEndEncountered, NSStreamEventErrorOccurred -> {
                        if (!completion.isCompleted) {
                            completion.completeExceptionally(Exception("Stream ended/errored before reading all data"))
                        }
                    }
                }
            }
        }
        stream.scheduleInRunLoop(NSRunLoop.mainRunLoop, NSDefaultRunLoopMode)
        stream.open()

        val result = withTimeout(5.seconds) { awaitWithRunLoop(completion) }
        assertTrue(data.contentEquals(result))
        stream.close()
    }

    @Test
    fun errorCallbackInvoked() = runTest {
        val errorCompletion = CompletableDeferred<Throwable>()
        val source = FailingAsyncSource(RuntimeException("Test error"))
        val stream = AsyncSourceInputStream(source) { error ->
            errorCompletion.complete(error)
        }
        val streamCompletion = CompletableDeferred<Unit>()

        stream.delegate = object : NSObject(), NSStreamDelegateProtocol {
            override fun stream(aStream: NSStream, handleEvent: NSStreamEvent) {
                if (handleEvent == NSStreamEventErrorOccurred) {
                    streamCompletion.complete(Unit)
                }
            }
        }
        stream.scheduleInRunLoop(NSRunLoop.mainRunLoop, NSDefaultRunLoopMode)
        stream.open()

        withTimeout(5.seconds) { awaitWithRunLoop(streamCompletion) }

        val error = errorCompletion.await()
        assertTrue(error is RuntimeException)
        assertEquals("Test error", error.message)
        assertEquals(NSStreamStatusError, stream.streamStatus())
        assertNotNull(stream.streamError())

        stream.close()
    }

    // ==================== Status-based tests ====================

    @Test
    fun streamStatusTransitions() = runTest {
        val source = ByteArrayAsyncSource("test".encodeToByteArray())
        val stream = AsyncSourceInputStream(source) {}

        assertEquals(NSStreamStatusNotOpen, stream.streamStatus())
        stream.scheduleInRunLoop(NSRunLoop.mainRunLoop, NSRunLoopCommonModes)
        stream.open()

        assertEquals(NSStreamStatusOpen, stream.streamStatus())

        stream.close()
        assertEquals(NSStreamStatusClosed, stream.streamStatus())
    }

    @Test
    fun closeBeforeRead() = runTest {
        val source = ByteArrayAsyncSource("test data".encodeToByteArray())
        val stream = AsyncSourceInputStream(source) {}

        stream.scheduleInRunLoop(NSRunLoop.mainRunLoop, NSRunLoopCommonModes)
        stream.open()
        stream.close()

        memScoped {
            val buffer = allocArray<uint8_tVar>(10)
            val bytesRead = stream.read(buffer, 10u)
            assertEquals(-1L, bytesRead, "Should return -1 when closed")
        }
    }

    @Test
    fun readAfterStreamEnds() = runTest {
        val source = ByteArrayAsyncSource(ByteArray(0))
        val stream = AsyncSourceInputStream(source) {}
        val completion = CompletableDeferred<Unit>()

        stream.delegate = object : NSObject(), NSStreamDelegateProtocol {
            override fun stream(aStream: NSStream, handleEvent: NSStreamEvent) {
                if (handleEvent == NSStreamEventEndEncountered) {
                    completion.complete(Unit)
                }
            }
        }
        stream.scheduleInRunLoop(NSRunLoop.mainRunLoop, NSDefaultRunLoopMode)
        stream.open()

        withTimeout(5.seconds) { awaitWithRunLoop(completion) }
        assertEquals(NSStreamStatusAtEnd, stream.streamStatus())

        memScoped {
            val buffer = allocArray<uint8_tVar>(10)
            val bytesRead = stream.read(buffer, 10u)
            assertEquals(0L, bytesRead, "Should return 0 for EOF")
        }

        stream.close()
    }

    @Test
    fun readNullBuffer() = runTest {
        val source = ByteArrayAsyncSource("test".encodeToByteArray())
        val stream = AsyncSourceInputStream(source) {}

        stream.scheduleInRunLoop(NSRunLoop.mainRunLoop, NSRunLoopCommonModes)
        stream.open()

        val bytesRead = stream.read(null, 10u)
        assertEquals(-1L, bytesRead, "Should return -1 for null buffer")

        stream.close()
    }

    @Test
    fun hasBytesAvailableWhenOpen() = runTest {
        val source = ByteArrayAsyncSource("test".encodeToByteArray())
        val stream = AsyncSourceInputStream(source) {}

        stream.scheduleInRunLoop(NSRunLoop.mainRunLoop, NSRunLoopCommonModes)
        stream.open()

        assertEquals(true, stream.hasBytesAvailable())

        stream.close()
    }

    @Test
    fun getBufferAlwaysReturnsFalse() = runTest {
        val source = ByteArrayAsyncSource("test".encodeToByteArray())
        val stream = AsyncSourceInputStream(source) {}

        stream.scheduleInRunLoop(NSRunLoop.mainRunLoop, NSRunLoopCommonModes)
        stream.open()

        assertEquals(false, stream.getBuffer(null, null))

        stream.close()
    }

    /**
     * Simple AsyncSource backed by a ByteArray.
     */
    private class ByteArrayAsyncSource(private val data: ByteArray) : AsyncSource {
        private var offset = 0

        override suspend fun read(sink: Buffer, byteCount: Long): Long {
            if (offset >= data.size) return -1L
            val toRead = minOf(byteCount, (data.size - offset).toLong()).toInt()
            sink.write(data, offset, toRead)
            offset += toRead
            return toRead.toLong()
        }

        override fun close() {}
    }

    /**
     * AsyncSource that fails on first read.
     */
    private class FailingAsyncSource(private val error: Throwable) : AsyncSource {
        override suspend fun read(sink: Buffer, byteCount: Long): Long {
            throw error
        }

        override fun close() {}
    }
}
