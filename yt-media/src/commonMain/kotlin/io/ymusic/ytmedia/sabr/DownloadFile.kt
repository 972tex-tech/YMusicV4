package io.ymusic.ytmedia.sabr

import okio.BufferedSink

/**
 * A growable byte destination for one format stream (audio or video), append-only. Bytes handed to
 * [SabrSession] are the format's contiguous stream (init segment at offset 0, then segments in
 * order) — byte-identical to the progressive file. See docs/player/sabr-protocol.md §4.
 */
interface DownloadFile {
    /** Bytes already written — the resume point. */
    fun length(): Long

    /** Append sink positioned at the current end of file. May be called repeatedly. */
    fun appendSink(): BufferedSink

    fun close()
}
