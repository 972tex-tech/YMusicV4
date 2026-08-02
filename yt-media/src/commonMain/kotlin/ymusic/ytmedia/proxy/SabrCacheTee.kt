package ymusic.ytmedia.proxy

import kmpcommon.io.AsyncSource
import okio.Buffer
import okio.EOFException
import ymusic.ytmedia.cache.MediaCache
import ymusic.ytmedia.cache.SegmentFill
import ymusic.ytmedia.proto.ProtoReader
import ymusic.ytmedia.sabr.MediaHeader
import ymusic.ytmedia.ump.UmpPart
import ymusic.ytmedia.ump.readUmpPartHeader

/**
 * Tees a SABR `/videoplayback` UMP response into the player cache **while passing the raw bytes through
 * to the WebView untouched** — the fill-on-forward counterpart of [CachingSource] for SABR (mirrors
 * legacy `SabrCacheInputStream.cacheResponseBody`: the player gets the exact network bytes, a copy is
 * UMP-parsed to fill the cache; only complete segments commit).
 *
 * The response is a stream of UMP parts (`varint partId`, `varint size`, payload). A segment is
 * `MEDIA_HEADER` (its [MediaHeader] proto) → one or more `MEDIA_DATA` parts (each `1 headerId byte` +
 * media bytes) → `MEDIA_END` (`1 headerId byte`). On `MEDIA_HEADER` we open a [SegmentFill] at the
 * segment's `[startRange, startRange+contentLength)`, stream each `MEDIA_DATA` payload into it, and
 * `commit()` on `MEDIA_END`; a segment left open when the stream ends (truncated) is `abort()`ed so no
 * partial span survives (the [MediaBlobCache] writer no-ops a segment already cached / being written).
 *
 * The parser runs incrementally off a *copy* of each read's bytes, so pass-through is byte-exact and
 * never reframed. A parse error, a cache-write failure, or an inconsistent-framing check flips
 * [illegalState] — open fills are aborted and caching is disabled for the rest of this response — but the
 * pass-through is left intact: a cache miss is always safe, caching corrupt bytes or corrupting the
 * player's stream is not.
 */
internal class SabrCacheTee(
    private val network: AsyncSource,
    private val cache: MediaCache,
    private val log: (String) -> Unit = {},
) : AsyncSource {

    /** A segment being mirrored: its writer plus how many media bytes are still expected. */
    private class OpenSeg(val fill: SegmentFill, var remaining: Long)

    private val scan = Buffer() // undispatched bytes (at most a part header + one MEDIA_HEADER payload)
    private val open = mutableMapOf<Int, OpenSeg>()

    private var inPayload = false
    private var partId = 0
    private var partRemaining = 0L
    private var headerBuf: Buffer? = null // accumulates a MEDIA_HEADER's proto until complete
    private var dataNeedId = false // next MEDIA_DATA byte is the 1-byte headerId, not media
    private var curSeg: OpenSeg? = null // the OpenSeg the current MEDIA_DATA feeds

    private var illegalState = false // corrupt/inconsistent stream or a cache-write error — stop caching
    private var finished = false

    override suspend fun read(sink: Buffer, byteCount: Long): Long {
        val n = network.read(sink, byteCount)
        if (n == -1L) {
            finish() // clean EOF: any still-open segment is truncated → drop it
            return -1L
        }
        if (!illegalState) {
            val copy = Buffer()
            sink.copyTo(copy, sink.size - n, n) // the bytes just appended to sink
            try {
                scan.writeAll(copy)
                dispatch()
            } catch (t: Throwable) {
                // A thrown parse (bad varint / proto) or cache write (disk / index) — never trust the rest.
                markIllegal("parse or cache-write error: ${t.message}")
            }
        }
        return n
    }

    override fun close() {
        network.close()
        finish()
    }

    /** Consume as many complete UMP structures as [scan] holds, opening/filling/committing segments. All
     *  blocking (okio writes, `@WorkerThread` commit) — non-suspend; only [read] suspends, on `network.read`. */
    private fun dispatch() {
        while (!illegalState) {
            if (!inPayload) {
                // A part header is (varint partId, varint size). Probe a peek so a straddling header
                // is left in [scan] for the next read instead of being half-consumed.
                val probe = scan.peek()
                val header = try {
                    probe.readUmpPartHeader()
                } catch (e: EOFException) {
                    return // full header not here yet
                }
                scan.readUmpPartHeader() // enough bytes present → consume for real
                partId = header.partId
                partRemaining = header.size
                inPayload = true
                startPart()
            }
            if (illegalState || !consumePayload()) return // startPart may have tripped illegalState
        }
    }

    private fun startPart() {
        when (partId) {
            UmpPart.MEDIA_HEADER -> headerBuf = Buffer()
            UmpPart.MEDIA_DATA -> {
                // Each MEDIA_DATA re-declares its target (a leading headerId) and is self-contained; the
                // previous data part clears [curSeg] when it ends ([endPart]). A non-null one here means a
                // MEDIA_DATA began while another was unfinished — framing lost, don't trust the bytes.
                if (curSeg != null) return markIllegal("MEDIA_DATA began with an unfinished segment")
                dataNeedId = true
            }
            // MEDIA_END / control parts need no per-start state.
        }
    }

    /** Feed available payload bytes for the current part; returns false when it needs more bytes. */
    private fun consumePayload(): Boolean {
        if (partRemaining == 0L) return endPart()
        if (scan.size == 0L) return false
        when (partId) {
            UmpPart.MEDIA_HEADER -> {
                val take = minOf(partRemaining, scan.size)
                headerBuf!!.write(scan, take)
                partRemaining -= take
            }

            UmpPart.MEDIA_DATA -> {
                if (dataNeedId) {
                    val id = scan.readByte().toInt() and 0xFF // headerId — NOT a media byte
                    partRemaining -= 1
                    dataNeedId = false
                    curSeg = open[id]
                    // Every MEDIA_HEADER opens an entry, so an unknown id means its header never arrived or
                    // its MEDIA_END already closed it (data-after-end) — either way the framing is lost.
                    if (curSeg == null) {
                        markIllegal("MEDIA_DATA for unopened id $id")
                        return false
                    }
                } else {
                    val seg = curSeg!! // set above; an unknown id already tripped illegalState
                    // This part still owes [partRemaining] media bytes and the segment expects [seg.remaining]
                    // more; more in the part than the segment can take overruns the declared contentLength —
                    // the mirror image of a short MEDIA_END, and just as corrupt.
                    if (partRemaining > seg.remaining) {
                        markIllegal("MEDIA_DATA overruns id: $partRemaining owed vs ${seg.remaining} expected")
                        return false
                    }
                    val take = minOf(partRemaining, scan.size)
                    seg.fill.sink.write(scan, take)
                    seg.remaining -= take
                    partRemaining -= take
                }
            }

            UmpPart.MEDIA_END -> {
                // A MEDIA_END is exactly its 1-byte headerId; any other size is malformed framing (and would
                // otherwise re-read the trailing bytes as further "ids").
                if (partRemaining != 1L) {
                    markIllegal("MEDIA_END payload is $partRemaining bytes, expected 1")
                    return false
                }
                val id = scan.readByte().toInt() and 0xFF
                partRemaining -= 1
                val seg = open.remove(id)
                if (seg == null) {
                    // No open segment for this id: its MEDIA_HEADER never arrived, or this is a second
                    // MEDIA_END closing it again (double-close) — either way the framing is lost.
                    markIllegal("MEDIA_END for unopened id $id")
                    return false
                }
                if (seg.remaining > 0L) {
                    // Short of the declared contentLength: a wrong-length span would desync the index, so drop
                    // it and stop caching rather than commit a truncated segment.
                    seg.fill.abort()
                    markIllegal("MEDIA_END for id $id short by ${seg.remaining} bytes")
                    return false
                }
                seg.fill.commit() // a throw here propagates to read()'s catch → markIllegal
            }

            else -> {
                val take = minOf(partRemaining, scan.size)
                scan.skip(take)
                partRemaining -= take
            }
        }
        if (partRemaining == 0L) return endPart()
        return true
    }

    /** Finish the current part (parse a completed MEDIA_HEADER → open a fill) and rearm for the next. */
    private fun endPart(): Boolean {
        when (partId) {
            UmpPart.MEDIA_HEADER -> {
                val bytes = headerBuf!!.readByteArray()
                headerBuf = null
                // A malformed header proto throws here → read()'s catch flips illegalState (don't cache a
                // segment whose byte range we can't trust).
                val header = MediaHeader.from(ProtoReader(bytes).readAllFields())
                if (open.containsKey(header.headerId)) {
                    // A second live segment on the same id before the first's MEDIA_END — lost framing.
                    markIllegal("duplicate MEDIA_HEADER id ${header.headerId}, still open")
                } else {
                    // Row saved now, span sealed on MEDIA_END (writer no-ops if already cached/in-flight).
                    open[header.headerId] = OpenSeg(cache.openSegmentFill(header), header.contentLength)
                }
            }
            // A data part is self-contained; clear the pointer so the next MEDIA_DATA starts from null
            // (the invariant [startPart] checks). Robust to a segment's media spanning several parts and to
            // interleaved segments — each part re-derives its target from its own headerId.
            UmpPart.MEDIA_DATA -> curSeg = null
        }
        inPayload = false
        return true
    }

    private fun finish() {
        if (finished) return
        finished = true
        abortOpen() // a segment still open at EOF/close is truncated (no MEDIA_END) — drop it
    }

    private fun abortOpen() {
        open.values.forEach { runCatching { it.fill.abort() } }
        open.clear()
    }

    /** Corrupt/inconsistent stream (or a cache-write failure): drop every open fill and stop caching for the
     *  rest of this response. Pass-through to the player is untouched — only the tee gives up. Idempotent. */
    private fun markIllegal(reason: String) {
        if (illegalState) return
        illegalState = true
        log("sabr-fill disabled (illegal state): $reason")
        abortOpen()
    }
}
