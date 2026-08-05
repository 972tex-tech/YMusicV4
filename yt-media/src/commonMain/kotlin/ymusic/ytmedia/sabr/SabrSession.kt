package ymusic.ytmedia.sabr

import kmpcommon.io.BufferedAsyncSource
import kmpcommon.io.asAsyncSource
import kmphttp.KmpHttpClient
import kmphttp.MediaTypeX.toMediaTypeOrNull
import kmphttp.RedirectOptions
import kmphttp.RequestBodyX.toRequestBody
import kmphttp.RequestBuilder
import kmphttp.asyncSource
import kmphttp.header
import kotlinx.coroutines.delay
import okio.Buffer
import okio.BufferedSink
import okio.IOException
import okio.Sink
import okio.Timeout
import okio.buffer
import okio.use
import ymusic.ytmedia.cache.MediaCache
import ymusic.ytmedia.cache.SabrServe
import ymusic.ytmedia.cache.SegmentFill
import ymusic.ytmedia.cache.SegmentIndex
import ymusic.ytmedia.cache.serveSabr
import ymusic.ytmedia.proto.ProtoField
import ymusic.ytmedia.proto.ProtoReader
import ymusic.ytmedia.ump.UmpPart
import ymusic.ytmedia.ump.copyFully
import ymusic.ytmedia.ump.readBytesFully
import ymusic.ytmedia.ump.readUmpPartHeader
import ymusic.ytmedia.ump.skipFully
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** One format stream to download and where its bytes go. */
class SabrTrack(val format: AdaptiveFormat, val file: DownloadFile)

/** Thrown when the server rejects the PO token (STREAM_PROTECTION_STATUS 2/3). Caller re-attests. */
class SabrPoTokenExpired(message: String) : IOException(message)

/** Thrown on UMP part 44. */
class SabrServerError(message: String) : IOException(message)

fun interface SabrProgress {
    fun onProgress(audioDownloaded: Long, audioLength: Long, videoDownloaded: Long, videoLength: Long)
}

/**
 * The SABR session loop (docs/player/sabr-protocol.md §4), ported from legacy
 * `SabrDownloader.sabrDownloader`. Pure protocol over the app's standard [KmpHttpClient]: the caller
 * configures the client (cookie jar, timeouts) and passes it plus the matching User-Agent to
 * [download]; byte sinks are [DownloadFile]s. Drives one or two format streams to completion.
 *
 * One instance is one download session (holds cpn / rn / segment cursor / playback cookie), not
 * reusable across videos.
 */
class SabrSession(
    private val random: Random = Random,
    /** Viewport reported to the ABR planner; 640×360 like legacy. */
    private val viewportWidth: Int = 640,
    private val viewportHeight: Int = 360,
    private val maxConsecutiveEmptyRounds: Int = 3,
    /** Optional per-round diagnostics. */
    private val log: (String) -> Unit = {},
) {
    private val cpn = generateCpn(random)
    private var rn = 0
    private var playbackCookie = ByteArray(0)
    private val sabrContextUpdates = mutableMapOf<Int, SabrContextUpdate>()
    private var poTokenValid = true

    // Session-level cursor: the last completed segment per track. Advanced in place across requests,
    // so segment N+1 is recognized as continuing N (a per-request cursor would reset and stall).
    private var audioSegment: MediaHeader? = null
    private var videoSegment: MediaHeader? = null

    suspend fun download(
        client: KmpHttpClient,
        userAgent: String,
        spec: SabrStreamSpec,
        audio: SabrTrack,
        video: SabrTrack? = null,
        progress: SabrProgress? = null,
        /** When set, every fully-fetched segment is mirrored into the player cache as it downloads. */
        mediaCache: MediaCache? = null,
        /** Persistent segment index (byte range per segment). Records each header as it downloads and, on
         *  start, seeds the resume cursor by querying the on-disk file length — so pause/resume and
         *  PO-token re-attest continue from the last complete segment instead of re-fetching from zero. */
        segmentIndex: SegmentIndex? = null,
    ) {
        // Seed the cursor: the last COMPLETE segment ends at/before (fileLength - 1). A partial next
        // segment is excluded (its r_end > fileLength - 1) and writeMediaData reconciles its bytes when the
        // server resends it. markDownloadTime protects these rows from cache retention cleanup.
        audioSegment = seedCursor(segmentIndex, audio)
        videoSegment = video?.let { seedCursor(segmentIndex, it) }
        segmentIndex?.markDownloadTime(audio.format.itag, audio.format.lmt)
        video?.let { segmentIndex?.markDownloadTime(it.format.itag, it.format.lmt) }
        val startMark = TimeSource.Monotonic.markNow()
        var url = spec.sabrUrl.setQueryParam("alr", "yes")

        // Emit as bytes land (throttled to [PROGRESS_INTERVAL]) so the bar moves smoothly across a long
        // round or a large segment, without a DB write per data chunk on a fast connection. [force] emits
        // the final value regardless of the throttle.
        var lastProgress = startMark
        fun emitProgress(force: Boolean = false) {
            if (!force && lastProgress.elapsedNow() < PROGRESS_INTERVAL) return
            lastProgress = TimeSource.Monotonic.markNow()
            progress?.onProgress(
                audio.file.length(), audio.format.clen,
                video?.file?.length() ?: 0, video?.format?.clen ?: 0,
            )
        }
        emitProgress(force = true)
        if (isComplete(audio, video)) return

        // A video format must be offered even when downloading audio-only, or the request fails.
        val fallbackVideoFormat = video?.format ?: spec.formats.lastOrNull { it.isVideo }

        var retryCount = 0
        var lastError: String? = null

        while (true) {
            if (retryCount > maxConsecutiveEmptyRounds) {
                if (!poTokenValid) throw SabrPoTokenExpired("PO token rejected; re-attest and retry")
                throw IOException("SABR exceeded max retries, last: $lastError")
            }

            val request = nextRequest(spec, audio, video, fallbackVideoFormat, startMark.elapsedNow().inWholeMilliseconds.toInt())

            // Reuse the player cache first: if it can serve this request's segments locally, parse that
            // synthesized UMP instead of hitting the network. A re-download of an already-cached track is
            // served entirely from cache (zero network); a partial/absent cache falls through below.
            val served = mediaCache?.let { runCatching { it.serveSabr(request) }.getOrNull() }
            if (served is SabrServe.Hit) {
                log("cache-serve rn=$rn playerTime=${request.clientAbrState.playerTimeMs} segments=${served.segmentCount} bytes=${served.cachedBytes}")
                val outcome = BufferedAsyncSource(served.ump.asAsyncSource()).use {
                    readResponse(it, audio, video, mediaCache, segmentIndex, onProgress = { emitProgress() })
                }
                if (isComplete(audio, video)) { emitProgress(force = true); return }
                if (outcome.completedSegments == 0) { retryCount++; lastError = "cache served zero segments" } else retryCount = 0
                continue
            }

            val requestUrl = url
                .setQueryParam("cpn", cpn)
                .setQueryParam("rn", (++rn).toString())
            log(
                "round rn=$rn playerTime=${request.clientAbrState.playerTimeMs} " +
                    "audioSeg=${audioSegment?.segmentIndex} videoSeg=${videoSegment?.segmentIndex} " +
                    "selected=${request.selectedFormats.size} cookieLen=${playbackCookie.size}",
            )

            val httpRequest = RequestBuilder()
                .url(requestUrl)
                .header("user-agent", userAgent)
                .post(request.encode().toByteArray().toRequestBody(PROTOBUF))
                .build()
            val response = client.execute(httpRequest, RedirectOptions(followRedirects = false, followSslRedirects = false))

            var outcome: RoundOutcome? = null
            try {
                if (response.isRedirect) {
                    val location = response.header("location")
                    if (location == null || !location.contains("/videoplayback")) {
                        throw IOException("Invalid SABR redirect: $location")
                    }
                    url = location
                } else if (!response.isSuccessful) {
                    throw IOException("SABR response ${response.code}")
                } else {
                    outcome = BufferedAsyncSource(response.body.asyncSource()).use {
                        readResponse(it, audio, video, mediaCache, segmentIndex, onProgress = { emitProgress() })
                    }
                }
            } finally {
                response.close()
            }
            if (outcome == null) continue // HTTP redirect — retry at the new url

            log(
                "  resp headers=${outcome.headers} seg=[${outcome.segRange()}] completed=${outcome.completedSegments} " +
                    "backoffMs=${outcome.backoffMs} protection=${outcome.protectionStatus} audioBytes=${audio.file.length()}",
            )
            if (outcome.redirect != null) {
                url = outcome.redirect
                continue
            }
            if (isComplete(audio, video)) { emitProgress(force = true); return }

            if (outcome.completedSegments == 0) {
                retryCount++
                lastError = "received zero segments"
                delay(maxOf(outcome.backoffMs, 2000).toLong())
            } else {
                retryCount = 0
            }
        }
    }

    /** Build the next request from the current session cursor (buffered ranges + playerTime). */
    internal fun nextRequest(
        spec: SabrStreamSpec,
        audio: SabrTrack,
        video: SabrTrack?,
        fallbackVideoFormat: AdaptiveFormat?,
        elapsedWallMs: Int,
    ): VideoPlaybackAbrRequest {
        val audioBuffered = bufferedRange(audioSegment)
        val videoBuffered = if (video != null) bufferedRange(videoSegment) else null
        var playerTimeMs = audioBuffered?.let { it.endMs + 1 } ?: 0
        if (video != null) {
            playerTimeMs = minOf(playerTimeMs, videoBuffered?.let { it.endMs + 1 } ?: 0)
        }
        val timeSinceLastSeek = random.nextInt(50) + 1 // must be > 0
        return VideoPlaybackAbrRequest(
            clientAbrState = ClientAbrState(
                playerTimeMs = playerTimeMs,
                enabledTrackTypesBitfield = if (video != null) 0 else 1,
                drcEnabled = audio.format.isDrc,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                timeSinceLastSeek = timeSinceLastSeek,
                visibility = 0,
                elapsedWallTimeMs = elapsedWallMs,
                timeSinceLastAction = random.nextInt(timeSinceLastSeek),
            ),
            selectedFormats = listOfNotNull(
                audio.format.toFormatId().takeIf { audioSegment != null },
                video?.format?.toFormatId()?.takeIf { videoSegment != null },
            ),
            bufferedRanges = listOfNotNull(audioBuffered, videoBuffered),
            selectedAudioFormats = listOf(audio.format.toFormatId()),
            selectedVideoFormats = listOfNotNull(
                video?.format?.toFormatId() ?: fallbackVideoFormat?.toFormatId(),
            ),
            streamerContext = StreamerContext(
                clientInfo = spec.clientInfo,
                poToken = spec.poToken,
                playbackCookie = playbackCookie,
                sabrContextUpdates = sabrContextUpdates.values.toList(),
            ),
            ustreamerConfig = spec.ustreamerConfig,
        )
    }

    private class PartState(val header: MediaHeader, val file: DownloadFile) {
        var sink: BufferedSink? = null
        var totalRead: Long = 0
        /** Set when this segment is also being mirrored into the player cache (see readResponse). */
        var cacheFill: SegmentFill? = null
    }

    internal class RoundOutcome(
        val completedSegments: Int,
        val redirect: String?,
        val backoffMs: Int,
        val headers: Int,
        val minSeg: Int,
        val maxSeg: Int,
        val protectionStatus: Int?,
    ) {
        fun segRange() = if (headers > 0) "$minSeg..$maxSeg" else "-"
    }

    /** Stream-parse one UMP response, advancing the session cursor; returns per-round outcome. */
    internal suspend fun readResponse(
        body: BufferedAsyncSource,
        audio: SabrTrack,
        video: SabrTrack?,
        mediaCache: MediaCache? = null,
        segmentIndex: SegmentIndex? = null,
        onProgress: () -> Unit = {},
    ): RoundOutcome {
        val open = mutableMapOf<Int, PartState>()
        var completed = 0
        var backoffMs = 0
        var redirect: String? = null
        var headers = 0
        var minSeg = Int.MAX_VALUE
        var maxSeg = Int.MIN_VALUE
        var protectionStatus: Int? = null
        try {
            while (!body.exhausted()) {
                val (partId, partSize) = body.readUmpPartHeader()
                when (partId) {
                    UmpPart.SABR_ERROR ->
                        throw SabrServerError("SABR error: ${SabrParts.parseErrorMessage(body.readBytesFully(partSize))}")

                    UmpPart.SABR_REDIRECT -> {
                        val r = SabrParts.parseRedirectUrl(body.readBytesFully(partSize))
                        if (r == null || !r.contains("/videoplayback")) throw IOException("Invalid SABR redirect part: $r")
                        redirect = r
                        break
                    }

                    UmpPart.MEDIA_HEADER -> {
                        val header = MediaHeader.from(body.readFields(partSize))
                        segmentIndex?.save(header) // persist the byte range for resume (INSERT-OR-IGNORE)
                        check(!open.containsKey(header.headerId)) { "duplicate UMP header id ${header.headerId}" }
                        headers++
                        minSeg = minOf(minSeg, header.segmentIndex)
                        maxSeg = maxOf(maxSeg, header.segmentIndex)
                        val file = when (header.cacheKey) {
                            audio.format.cacheKey -> audio.file
                            video?.format?.cacheKey -> video.file
                            else -> throw IOException("unexpected media header ${header.videoId} ${header.cacheKey} seg ${header.segmentIndex}")
                        }
                        val state = PartState(header, file)
                        // Mirror into the player cache only when this file is exactly at the segment's start
                        // — a fresh, full write with no server-resent prefix to skip. That keeps the cache
                        // span's declared range equal to the bytes we actually store (per DownloadFile the
                        // file position IS the format's contiguous byte offset). A resumed/overlapping
                        // segment is left uncached rather than mislabeled.
                        if (mediaCache != null && file.length() == header.startRange) {
                            state.cacheFill = mediaCache.openSegmentFill(header)
                        }
                        open[header.headerId] = state
                    }

                    UmpPart.MEDIA_DATA -> {
                        val headerId = body.readByte().toInt() and 0xFF
                        val state = checkNotNull(open[headerId]) { "media data for unknown header $headerId" }
                        val dataSize = partSize - 1
                        writeMediaData(body, state, dataSize)
                        state.totalRead += dataSize
                        onProgress() // throttled by the caller; reflects the growing file length
                    }

                    UmpPart.MEDIA_END -> {
                        val headerId = body.readByte().toInt() and 0xFF
                        val state = checkNotNull(open.remove(headerId)) { "media end for unknown header $headerId" }
                        state.sink?.close()
                        state.sink = null
                        state.cacheFill?.commit() // seal the mirrored span (its index row was saved on the header)
                        state.cacheFill = null
                        if (advanceIfContiguous(state.header, audio, video)) completed++
                    }

                    UmpPart.NEXT_REQUEST_POLICY -> {
                        val policy = NextRequestPolicy.from(body.readFields(partSize))
                        policy.playbackCookie?.let { playbackCookie = it }
                        backoffMs = policy.backoffTimeMs ?: 0
                    }

                    UmpPart.STREAM_PROTECTION_STATUS -> {
                        val status = SabrParts.parseStreamProtectionStatus(body.readBytesFully(partSize))
                        protectionStatus = status
                        if (status != null) poTokenValid = status != 2 && status != 3
                    }

                    UmpPart.SABR_CONTEXT_UPDATE -> {
                        val update = runCatching {
                            SabrContextUpdate.fromUmpResponse(body.readFields(partSize))
                        }.getOrNull()
                        if (update != null) sabrContextUpdates[update.type] = update
                    }

                    else -> body.skipFully(partSize)
                }
            }
        } finally {
            open.values.forEach { state ->
                state.sink?.let { s -> runCatching { s.close() } }
                // A segment left open (truncated response) never sealed: drop its uncommitted cache block
                // so no partial span survives; its orphan index row is pruned later by cleanup.
                state.cacheFill?.let { runCatching { it.abort() } }
            }
        }
        return RoundOutcome(completed, redirect, backoffMs, headers, minSeg, maxSeg, protectionStatus)
    }

    /**
     * Write one MEDIA_DATA chunk, skipping any prefix the file already has (server may resend bytes
     * inside our buffered range). Mirrors legacy's byte-position reconciliation.
     */
    private suspend fun writeMediaData(body: BufferedAsyncSource, state: PartState, dataSize: Long) {
        state.sink?.let { sink ->
            body.copyFully(sink, dataSize)
            return
        }
        val fileEnd = state.file.length()
        val partStart = state.header.startRange + state.totalRead
        val partEnd = partStart + dataSize
        if (fileEnd in partStart until partEnd) {
            val skips = fileEnd - partStart
            if (skips > 0) body.skipFully(skips)
            val fileSink = state.file.appendSink()
            // Mirror into the cache span too when this segment is being cached. cacheFill is only set for
            // segments with no skip (file at startRange), so the tee sees the segment's full bytes.
            val sink = state.cacheFill?.let { TeeSink(fileSink, it.sink).buffer() } ?: fileSink
            state.sink = sink
            body.copyFully(sink, dataSize - skips)
        } else {
            if (fileEnd < partStart) throw IOException("gap in download stream at ${state.header.itag}")
            body.skipFully(dataSize) // already have these bytes
        }
    }

    /**
     * Parse a length-delimited part's fields in place from the shared async buffer (no slice copy),
     * but ALWAYS advance the buffer by exactly [size] — even if parsing throws or stops short. A
     * malformed control part (whose throw a caller may swallow, e.g. SABR_CONTEXT_UPDATE) must not
     * leave leftover bytes that desync the next `readUmpPartHeader`.
     */
    private suspend fun BufferedAsyncSource.readFields(size: Long): List<ProtoField> {
        require(size)
        val remainderAfter = buffer.size - size
        try {
            return ProtoReader(buffer, size).readAllFields()
        } finally {
            if (buffer.size > remainderAfter) buffer.skip(buffer.size - remainderAfter)
        }
    }

    private fun advanceIfContiguous(header: MediaHeader, audio: SabrTrack, video: SabrTrack?): Boolean {
        when (header.cacheKey) {
            audio.format.cacheKey ->
                if (header.segmentIndex - 1 == (audioSegment?.segmentIndex ?: -1)) {
                    audioSegment = header
                    return true
                }
            video?.format?.cacheKey ->
                if (header.segmentIndex - 1 == (videoSegment?.segmentIndex ?: -1)) {
                    videoSegment = header
                    return true
                }
        }
        return false
    }

    private fun bufferedRange(segment: MediaHeader?): BufferedRange? {
        val header = segment?.takeIf { !it.isInitSegment } ?: return null
        return BufferedRange(
            formatId = FormatId(header.itag, header.lmt, header.xtag ?: ""),
            startMs = 0,
            durationMs = checkNotNull(header.startMs) + checkNotNull(header.durationMs),
            startSegmentIndex = 1,
            endSegmentIndex = header.segmentIndex,
        )
    }

    /** The resume cursor for a track: the last complete segment covering the current file end, from the
     *  segment index — or null for a fresh (empty file) or un-indexed download. */
    private fun seedCursor(index: SegmentIndex?, track: SabrTrack): MediaHeader? {
        val length = track.file.length()
        return if (index == null || length <= 0) null
        else index.findByByteOffset(track.format.itag, track.format.lmt, length - 1)
    }

    private fun isComplete(audio: SabrTrack, video: SabrTrack?): Boolean =
        audio.file.length() >= audio.format.clen &&
            (video == null || video.file.length() >= video.format.clen)

    private companion object {
        private val PROTOBUF = "application/x-protobuf".toMediaTypeOrNull()

        /** Minimum gap between progress emits — caps DB/UI churn on a fast connection while staying smooth. */
        private val PROGRESS_INTERVAL = 150.milliseconds
    }
}

/**
 * Fans one write out to two [BufferedSink]s — the download's file and the cache span — so a segment's
 * bytes land in both in a single streaming pass. [close] closes only [primary] (the file); the cache
 * side's lifecycle is [SegmentFill.commit]/[SegmentFill.abort], so it is merely flushed here.
 */
private class TeeSink(
    private val primary: BufferedSink,
    private val secondary: BufferedSink,
) : Sink {
    override fun write(source: Buffer, byteCount: Long) {
        val copy = Buffer()
        source.copyTo(copy, 0, byteCount) // copy without consuming, so both sinks get the same bytes
        secondary.write(copy, byteCount)
        primary.write(source, byteCount)
    }

    override fun flush() {
        secondary.flush()
        primary.flush()
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        primary.close()
        secondary.flush()
    }
}
