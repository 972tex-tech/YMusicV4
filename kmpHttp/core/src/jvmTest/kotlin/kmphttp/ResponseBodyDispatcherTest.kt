package kmphttp

import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The JVM ResponseBody suspend readers wrap a BLOCKING okio/OkHttp read in runIoInterruptible,
 * whose context must default to Dispatchers.IO. With the old EmptyCoroutineContext default the
 * blocking read ran on the CALLER's thread — an ANR when the caller is Dispatchers.Main.
 */
class ResponseBodyDispatcherTest {

    /** A ResponseBody whose source records which thread the blocking read executes on. */
    private class ThreadRecordingBody(private val data: String) : okhttp3.ResponseBody() {
        @Volatile var readThread: String? = null
        override fun contentType() = "text/plain".toMediaTypeOrNull()
        override fun contentLength() = data.length.toLong()
        override fun source(): BufferedSource {
            val backing: Source = Buffer().writeUtf8(data)
            return object : ForwardingSource(backing) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    readThread = Thread.currentThread().name
                    return super.read(sink, byteCount)
                }
            }.buffer()
        }
    }

    @Test
    fun utf8String_read_runs_off_the_callers_confined_thread() = runBlocking {
        newSingleThreadContext("caller-only").use { caller ->
            withContext(caller) {
                val callerThread = Thread.currentThread().name
                val body = ThreadRecordingBody("hello")
                assertEquals("hello", body.utf8String())
                assertNotEquals(callerThread, body.readThread, "blocking read must not run on the caller thread")
            }
        }
    }
}
