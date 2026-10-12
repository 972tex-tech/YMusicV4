package io.ymusic.ui

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Outside links the app was opened with (an Android VIEW intent, a desktop/iOS custom-scheme open),
 * handed in by the shell and opened by [App] at the right page. Process-wide because a link can
 * arrive before the graph exists (a cold start): it waits here until [App] drains it.
 */
object IncomingLinks {
    private val channel = Channel<String>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    internal val links: Flow<String> = channel.receiveAsFlow()

    fun open(url: String) {
        channel.trySend(url)
    }
}
