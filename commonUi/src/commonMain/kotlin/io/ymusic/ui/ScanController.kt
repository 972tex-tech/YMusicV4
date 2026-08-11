package io.ymusic.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.library_scan_failed
import kmpcommon.error.CodedError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

/**
 * App-level owner of a library scan/rescan, so it outlives the Library screen: a scan started from
 * the Folders tab keeps running (and its result still reaches the user) after navigating to Discover.
 * Hosted on [AppRoot]'s scope; its [summaries] are piped to the app snackbar there, so the completion
 * message shows on whatever screen is open — a notification, not a Library-only banner.
 */
internal class ScanController(private val scope: CoroutineScope) {
    /** True while a scan runs; [scanned] is its running file count (null before the first tick). */
    var running by mutableStateOf(false)
        private set
    var scanned by mutableStateOf<Int?>(null)
        private set

    private val _summaries = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val summaries: SharedFlow<String> = _summaries.asSharedFlow()

    /** Start [action] unless one is already running; its summary (or error) surfaces on [summaries]. */
    fun start(action: ScanAction) {
        if (running) return
        running = true
        scanned = null
        scope.launch {
            try {
                action { count -> scanned = count }?.let { _summaries.tryEmit(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _summaries.tryEmit(getString(Res.string.library_scan_failed, (e as? CodedError)?.code ?: e.message ?: "$e"))
            } finally {
                running = false
                scanned = null
            }
        }
    }
}

/** The app-level [ScanController], provided by [AppRoot]. */
internal val LocalScanController = compositionLocalOf<ScanController?> { null }
