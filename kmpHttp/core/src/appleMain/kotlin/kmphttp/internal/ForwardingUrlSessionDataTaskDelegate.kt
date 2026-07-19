package kmphttp.internal

import kmphttp.AuthChallengeHandler
import kmphttp.SslException
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSLocalizedDescriptionKey
import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengeDisposition
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionResponseCancel
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskDelegateProtocol
import platform.darwin.NSObject

/** Domain of the substituted [NSError] for a server-trust rejection our own SslConfig issued. */
internal const val KMPHTTP_TLS_ERROR_DOMAIN = "kmphttp.ssl"

/** userInfo key carrying the original Kotlin exception through an in-process [NSError]. */
internal const val KMPHTTP_ERROR_EXCEPTION_KEY = "kmphttp.exception"

@OptIn(UnsafeNumber::class)
class ForwardingUrlSessionDataTaskDelegate(
    private val onBecomeInvalid: (session: NSURLSession, didBecomeInvalidWithError: NSError?) -> Unit,
    private val onReceiveChallenge: AuthChallengeHandler? = null,
) : NSObject(), NSURLSessionDataDelegateProtocol,
    NSURLSessionTaskDelegateProtocol {

    private val lock = SynchronizedObject()
    private val callbacks = mutableMapOf<String?, NSURLSessionDataDelegateProtocol>()

    // Tasks whose server-trust challenge OUR handler cancelled → the typed failure, constructed at
    // the rejection site. NSURLSession reports that refusal as a bare NSURLErrorCancelled (-999,
    // "cancelled"), so the exception is re-attached at completion or the caller sees a misleading
    // generic cancellation instead of a TLS failure.
    private val trustRejections = mutableMapOf<String?, SslException>()

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
            val rejection = trustRejections.remove(task.taskDescription)
            val effectiveError = if (didCompleteWithError != null && rejection != null) {
                // In-process only, so the Kotlin exception rides userInfo as-is (never serialized).
                NSError.errorWithDomain(
                    KMPHTTP_TLS_ERROR_DOMAIN,
                    code = 1,
                    userInfo = mapOf<Any?, Any?>(
                        NSLocalizedDescriptionKey to rejection.message.orEmpty(),
                        KMPHTTP_ERROR_EXCEPTION_KEY to rejection,
                    ),
                )
            } else {
                didCompleteWithError
            }
            val callback = callbacks[task.taskDescription]
            callback?.URLSession(
                session = session,
                task = task,
                didCompleteWithError = effectiveError
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
            val space = didReceiveChallenge.protectionSpace
            val isServerTrust = space.authenticationMethod == NSURLAuthenticationMethodServerTrust
            handler(didReceiveChallenge) { disposition, credential ->
                if (isServerTrust && disposition == NSURLSessionAuthChallengeCancelAuthenticationChallenge) {
                    val rejection =
                        SslException("server trust rejected for ${space.host} by SslConfig (anchors/hostname/pins)")
                    synchronized(lock) { trustRejections[task.taskDescription] = rejection }
                }
                completionHandler(disposition, credential)
            }
        } else {
            completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
        }
    }
}
