package kmphttp.internal

import kmpcommon.io.AsyncSource
import kmpcommon.kilobytes
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okio.Buffer
import platform.Foundation.NSCondition
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSInputStream
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopMode
import platform.Foundation.NSStream
import platform.Foundation.NSStreamDelegateProtocol
import platform.Foundation.NSStreamEvent
import platform.Foundation.NSStreamEventEndEncountered
import platform.Foundation.NSStreamEventErrorOccurred
import platform.Foundation.NSStreamEventHasBytesAvailable
import platform.Foundation.NSStreamEventOpenCompleted
import platform.Foundation.NSStreamPropertyKey
import platform.Foundation.NSStreamStatusAtEnd
import platform.Foundation.NSStreamStatusClosed
import platform.Foundation.NSStreamStatusError
import platform.Foundation.NSStreamStatusNotOpen
import platform.Foundation.NSStreamStatusOpen
import platform.Foundation.performInModes
import platform.darwin.NSInteger
import platform.darwin.NSUInteger
import platform.darwin.NSUIntegerVar
import platform.posix.uint8_tVar
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference

/**
 * NSInputStream implementation that reads from AsyncSource.
 * Used to stream request body to NSURLSession.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal class AsyncSourceInputStream(
    source: AsyncSource,
    private val onError: (Throwable) -> Unit
) : NSInputStream(NSData()), NSStreamDelegateProtocol {

    private val buffered = Buffer()
    private val bufferedLock = NSCondition()
    private var source: AsyncSource? = source
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val suspendCondition = SuspendCondition()

    private var currentStreamStatus = NSStreamStatusNotOpen
    private var currentStreamError: NSError? = null
    private var closed = false

    private inline fun <R> NSCondition.withLock(block: () -> R): R {
        try {
            lock()
            return block()
        } finally {
            unlock()
        }
    }

    override fun open() {
        currentStreamStatus = NSStreamStatusOpen
        notifyOpened()

        coroutineScope.launch {
            val tempBuffer = Buffer()
            try {
                var sourceEnded = false
                while (true) {
                    check(tempBuffer.size == 0L)
                    val preBufferSize = 16.kilobytes
                    while (buffered.size < preBufferSize && tempBuffer.size < preBufferSize && !sourceEnded) {
                        val bytesRead = source?.read(tempBuffer, preBufferSize) ?: -1L
                        if (bytesRead == -1L) {
                            sourceEnded = true
                            releaseSource()
                        }
                    }
                    val done = bufferedLock.withLock {
                        if (closed) return@launch

                        if (tempBuffer.size > 0) {
                            buffered.write(tempBuffer, tempBuffer.size)
                            check(tempBuffer.size == 0L)
                        }
                        if (buffered.size > 0) {
                            notifyBytesAvailable()
                            suspendCondition.pause()
                            bufferedLock.broadcast()
                            return@withLock false
                        } else {
                            check(sourceEnded)
                            currentStreamStatus = NSStreamStatusAtEnd
                            notifyBytesEnded()
                            bufferedLock.broadcast()
                            return@withLock true
                        }
                    }
                    if (done) {
                        break
                    } else {
                        suspendCondition.await()
                    }
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                bufferedLock.withLock {
                    if (!closed) {
                        currentStreamError = e.toNSError()
                        currentStreamStatus = NSStreamStatusError
                        notifyBytesError()
                        onError(e)
                    }
                    bufferedLock.broadcast()
                }
            } finally {
                tempBuffer.clear()
                releaseSource()
            }
        }
    }

    private fun notifyOpened() {
        postEvent(NSStreamEventOpenCompleted)
    }

    private fun notifyBytesAvailable() {
        postEvent(NSStreamEventHasBytesAvailable)
    }

    private fun notifyBytesEnded() {
        postEvent(NSStreamEventEndEncountered)
    }

    private fun notifyBytesError() {
        postEvent(NSStreamEventErrorOccurred)
    }

    override fun close() {
        bufferedLock.withLock {
            if (!closed) {
                closed = true
                currentStreamStatus = NSStreamStatusClosed
                coroutineScope.cancel()
                releaseSource()
                suspendCondition.release()
                bufferedLock.broadcast()
            }
        }
    }

    override fun read(buffer: CPointer<uint8_tVar>?, maxLength: NSUInteger): NSInteger {
        if (buffer == null || closed || currentStreamStatus == NSStreamStatusError) {
            return -1
        }

        var totalBytesWritten = 0L
        bufferedLock.withLock {
            while (totalBytesWritten == 0L) {
                if (closed || currentStreamStatus == NSStreamStatusError) {
                    return -1
                }
                if (buffered.size > 0L) {
                    totalBytesWritten = buffered.read(buffer, maxLength.toLong())
                    suspendCondition.resume()
                } else {
                    if (currentStreamStatus == NSStreamStatusAtEnd) {
                        return 0L // EOF
                    }
                    suspendCondition.resume()
                    bufferedLock.wait() // BLOCKING WAIT
                }
            }
        }

        return totalBytesWritten
    }

    override fun hasBytesAvailable(): Boolean = currentStreamStatus == NSStreamStatusOpen

    override fun streamStatus() = currentStreamStatus

    override fun streamError(): NSError? = currentStreamError

    private fun releaseSource() {
        source?.close()
        source = null
    }

    override fun getBuffer(
        buffer: CPointer<CPointerVar<uint8_tVar>>?,
        length: CPointer<NSUIntegerVar>?
    ): Boolean {
        return false
    }

    override fun propertyForKey(key: NSStreamPropertyKey): Any? = null
    override fun setProperty(property: Any?, forKey: NSStreamPropertyKey): Boolean = false

    private var _delegate: WeakReference<NSStreamDelegateProtocol>? = null
    private var runLoop: NSRunLoop? = null
    private var runLoopModes = listOf<NSRunLoopMode>()

    private fun postEvent(event: NSStreamEvent) {
        val runLoop = runLoop ?: return
        runLoop.performInModes(runLoopModes) {
            if (runLoop == this.runLoop) {
                delegateOrSelf.stream(this, event)
            }
        }
    }

    override fun delegate() = _delegate?.value

    private val delegateOrSelf get() = delegate ?: this

    override fun setDelegate(delegate: NSStreamDelegateProtocol?) {
        _delegate = delegate?.let { WeakReference(it) }
    }

    override fun stream(aStream: NSStream, handleEvent: NSStreamEvent) {
        // no-op
    }

    override fun scheduleInRunLoop(aRunLoop: NSRunLoop, forMode: NSRunLoopMode) {
        if (runLoop == null) {
            runLoop = aRunLoop
        }
        if (runLoop == aRunLoop) {
            runLoopModes += forMode
        }
        suspendCondition.resume()
    }

    override fun removeFromRunLoop(aRunLoop: NSRunLoop, forMode: NSRunLoopMode) {
        if (aRunLoop == runLoop) {
            runLoopModes -= forMode
            if (runLoopModes.isEmpty()) {
                runLoop = null
            } else {
                suspendCondition.resume()
            }
        }
    }

    override fun description(): String = "$source.inputStream()"
}
