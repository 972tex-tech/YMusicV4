package io.ymusic.ui

import androidx.compose.ui.platform.UriHandler
import kmpcommon.log.Logger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** A [UriHandler] that reports a link nothing on the device can open on [failures] — the app shell
 *  shows it — instead of throwing out of a click handler. Android wraps its ActivityNotFoundException
 *  in IllegalArgumentException; desktop throws UnsupportedOperationException, IOException or
 *  URISyntaxException. */
internal class SafeUriHandler(private val platform: UriHandler, private val log: Logger?) : UriHandler {
    private val failed = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val failures: SharedFlow<String> = failed

    override fun openUri(uri: String) {
        try {
            platform.openUri(uri)
        } catch (error: Exception) {
            log?.log(Logger.Level.WARN, "no app opens $uri", error)
            failed.tryEmit(uri)
        }
    }
}
