package io.ymusic.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSError
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAErrorAppCancel
import platform.LocalAuthentication.LAErrorPasscodeNotSet
import platform.LocalAuthentication.LAErrorSystemCancel
import platform.LocalAuthentication.LAErrorUserCancel
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalForeignApi::class)
internal suspend fun iosConfirmDeviceOwner(title: String, subtitle: String): Boolean =
    withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val ctx = LAContext()
            if (!ctx.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error = null)) {
                continuation.resumeWithException(IllegalStateException("no-lock"))
                return@suspendCancellableCoroutine
            }
            val reason = subtitle.ifBlank { title }
            ctx.evaluatePolicy(LAPolicyDeviceOwnerAuthentication, localizedReason = reason) { success, error ->
                if (!continuation.isActive) return@evaluatePolicy
                when {
                    success -> continuation.resume(true)
                    isAuthCancel(error) -> continuation.resume(false)
                    error?.code == LAErrorPasscodeNotSet ->
                        continuation.resumeWithException(IllegalStateException("no-lock"))
                    else -> continuation.resumeWithException(IllegalStateException("auth"))
                }
            }
        }
    }

private fun isAuthCancel(error: NSError?): Boolean {
    val code = error?.code ?: return false
    return code == LAErrorUserCancel || code == LAErrorSystemCancel || code == LAErrorAppCancel
}
