package io.ymusic.ui

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** What to do with an outside link's video; any other target just opens. */
enum class LinkAction {
    /** Play with its radio (or the link's playlist) and show Now Playing. */
    OPEN,
    PLAY_SINGLE,
    QUEUE_NEXT,
    QUEUE_LAST,

    /** The download options dialog. */
    DOWNLOAD,
    DOWNLOAD_M4A,
    DOWNLOAD_MP3,
}

internal data class IncomingLink(val url: String, val action: LinkAction)

/**
 * Outside links the app was opened with (an Android VIEW intent or share, a desktop/iOS
 * custom-scheme open), handed in by the shell and opened by [App] at the right page. Process-wide
 * because a link can arrive before the graph exists (a cold start): it waits here until [App] drains it.
 */
object IncomingLinks {
    private val channel = Channel<IncomingLink>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    internal val links: Flow<IncomingLink> = channel.receiveAsFlow()

    fun open(url: String, action: LinkAction = LinkAction.OPEN) {
        channel.trySend(IncomingLink(url, action))
    }
}
