package kmphttp.internal

import kmphttp.AuthChallengeHandler
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionAuthChallengeDisposition
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionResponseCancel
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskDelegateProtocol
import platform.darwin.NSObject


@OptIn(UnsafeNumber::class)
class ForwardingUrlSessionDataTaskDelegate(
    private val onBecomeInvalid: (session: NSURLSession, didBecomeInvalidWithError: NSError?) -> Unit,
    private val onReceiveChallenge: AuthChallengeHandler? = null,
) : NSObject(), NSURLSessionDataDelegateProtocol,
    NSURLSessionTaskDelegateProtocol {

    private val lock = SynchronizedObject()
    private val callbacks = mutableMapOf<String?, NSURLSessionDataDelegateProtocol>()

    fun register(taskDescription: String, callback: NSURLSessionDataDelegateProtocol) {
        synchronized(lock) {
            check(!callbacks.containsKey(taskDescription)) { "task $taskDescription existing" }
            callbacks[taskDescription] = callback
        }
    }

    fun unregister(taskDescription: String) {
        synchronized(lock) {
            callbacks.remove(taskDescription)
        }
    }

    // NSURLSessionDataDelegate - received response headers
    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveResponse: NSURLResponse,
        completionHandler: (NSURLSessionResponseDisposition) -> Unit
    ) {
        synchronized(lock) {
            val callback = callbacks[dataTask.taskDescription]
            if (callback != null) {
                callback.URLSession(
                    session = session,
                    dataTask = dataTask,
                    didReceiveResponse = didReceiveResponse,
                    completionHandler = completionHandler
                )
            } else {
                completionHandler(NSURLSessionResponseCancel)
            }
        }
    }

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveData: NSData
    ) {
        synchronized(lock) {
            val callback = callbacks[dataTask.taskDescription]
            if (callback != null) {
                callback.URLSession(
                    session = session,
                    dataTask = dataTask,
                    didReceiveData = didReceiveData
                )
            } else {
                dataTask.cancel()
            }
        }
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?
    ) {
        synchronized(lock) {
            val callback = callbacks[task.taskDescription]
            callback?.URLSession(
                session = session,
                task = task,
                didCompleteWithError = didCompleteWithError
            )
        }
    }

    override fun URLSession(session: NSURLSession, didBecomeInvalidWithError: NSError?) {
        onBecomeInvalid(session, didBecomeInvalidWithError)
        synchronized(lock) {
            callbacks.values.toList().forEach {
                it.URLSession(
                    session = session,
                    didBecomeInvalidWithError = didBecomeInvalidWithError
                )
            }
        }
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        willPerformHTTPRedirection: NSHTTPURLResponse,
        newRequest: NSURLRequest,
        completionHandler: (NSURLRequest?) -> Unit
    ) {
        synchronized(lock) {
            val callback = callbacks[task.taskDescription]
            if (callback != null) {
                callback.URLSession(
                    session = session,
                    task = task,
                    willPerformHTTPRedirection = willPerformHTTPRedirection,
                    newRequest = newRequest,
                    completionHandler = completionHandler
                )
            } else {
                completionHandler(null)
            }
        }
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didReceiveChallenge: NSURLAuthenticationChallenge,
        completionHandler: (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit
    ) {
        val handler = onReceiveChallenge
        if (handler != null) {
            handler(didReceiveChallenge, completionHandler)
        } else {
            completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
        }
    }
}