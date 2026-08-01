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
 * never reframed. Any parse error disables further parsing (open fills aborted) but leaves the
 * pass-through intact — a cache miss is always safe, corrupting the player's stream is not.
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

    private var broken = false
    private var finished = false

    override suspend fun read(sink: Buffer, byteCount: Long): Long {
        val n = network.read(sink, byteCount)
        if (n == -1L) {
            finish(commitOpen = false) // clean EOF: any still-open segment is truncated → drop it
            return -1L
        }
        if (!broken) {
            val copy = Buffer()
            sink.copyTo(copy, sink.size - n, n) // the bytes just appended to sink
            try {
                scan.writeAll(copy)
                dispatch()
            } catch (t: Throwable) {
                log("sabr-fill parse stopped: ${t.message}")
                broken = true
                abortOpen()
            }
        }
        return n
    }

    override fun close() {
        network.close()
        finish(commitOpen = false)
    }

    /** Consume as many complete UMP structures as [scan] holds, opening/filling/committing segments. */
    private suspend fun dispatch() {
        while (true) {
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
            if (!consumePayload()) return
        }
    }

    private fun startPart() {
        when (partId) {
            UmpPart.MEDIA_HEADER -> headerBuf = Buffer()
            UmpPart.MEDIA_DATA -> { dataNeedId = true; curSeg = null }
            // MEDIA_END / control parts need no per-start state.
        }
    }

    /** Feed available payload bytes for the current part; returns false when it needs more bytes. */
    private suspend fun consumePayload(): Boolean {
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
                } else {
                    val take = minOf(partRemaining, scan.size)
                    val seg = curSeg
                    if (seg != null && seg.remaining > 0) {
                        val w = minOf(take, seg.remaining)
                        seg.fill.sink.write(scan, w)
                        seg.remaining -= w
                        if (take > w) scan.skip(take - w) // media beyond declared contentLength — drop
                    } else {
                        scan.skip(take)
                    }
                    partRemaining -= take
                }
            }

            UmpPart.MEDIA_END -> {
                val id = scan.readByte().toInt() and 0xFF
                partRemaining -= 1
                open.remove(id)?.let { runCatching { it.fill.commit() } }
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
    private suspend fun endPart(): Boolean {
        if (partId == UmpPart.MEDIA_HEADER) {
            val bytes = headerBuf!!.readByteArray()
            headerBuf = null
            val header = runCatching { MediaHeader.from(ProtoReader(bytes).readAllFields()) }.getOrNull()
            if (header != null && !open.containsKey(header.headerId)) {
                // Row saved now, span sealed on MEDIA_END (writer no-ops if already cached/in-flight).
                open[header.headerId] = OpenSeg(cache.openSegmentFill(header), header.contentLength)
            }
        }
        inPayload = false
        return true
    }

    private fun finish(commitOpen: Boolean) {
        if (finished) return
        finished = true
        if (!commitOpen) abortOpen()
    }

    private fun abortOpen() {
        open.values.forEach { runCatching { it.fill.abort() } }
        open.clear()
    }
}
