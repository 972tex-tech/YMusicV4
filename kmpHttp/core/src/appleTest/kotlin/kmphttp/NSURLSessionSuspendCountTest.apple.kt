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
 * Documents that NSURLSessionTask's `state` is BOOLEAN: one resume() after any number of suspend()
 * calls reads Running again. Data delivery is NOT — suspends are counted, and a task suspended
 * twice and resumed once stays silent (verified on the iOS simulator, 2026-10-14). Pair them.
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
