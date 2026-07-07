package kmphttp

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionTaskStateRunning
import platform.Foundation.NSURLSessionTaskStateSuspended
import platform.Foundation.dataTaskWithURL
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Documents that NSURLSessionTask suspend/resume are a BOOLEAN state, not a cumulative count: a
 * single resume() clears any number of prior suspend() calls. (An earlier fix assumed they were
 * counted — this test shows they are not, so a handler that suspends more than it resumes does not
 * strand the task.)
 */
@OptIn(ExperimentalForeignApi::class)
class NSURLSessionSuspendCountTest {

    @Test
    fun oneResumeClearsMultipleSuspends() {
        val session = NSURLSession.sharedSession
        val url = NSURL(string = "https://127.0.0.1:1/")!! // never actually connects in this test
        val task = session.dataTaskWithURL(url)

        assertEquals(NSURLSessionTaskStateSuspended, task.state, "fresh task is suspended")
        task.resume()
        assertEquals(NSURLSessionTaskStateRunning, task.state, "resume() runs it")

        task.suspend()
        task.suspend()
        assertEquals(NSURLSessionTaskStateSuspended, task.state)

        // Boolean, not counted: a single resume after two suspends returns to Running.
        task.resume()
        assertEquals(NSURLSessionTaskStateRunning, task.state)

        task.cancel()
    }
}
