package ymusic.ytmedia.sabr

import kmpcommon.io.BufferedAsyncSource
import kmphttp.KmpHttpClient
import kmphttp.MediaTypeX.toMediaTypeOrNull
import kmphttp.RedirectOptions
import kmphttp.RequestBodyX.toRequestBody
import kmphttp.RequestBuilder
import kmphttp.asyncSource
import kmphttp.header
import kotlinx.coroutines.delay
import okio.BufferedSink
import okio.IOException
import okio.use
import ymusic.ytmedia.proto.ProtoField
import ymusic.ytmedia.proto.ProtoReader
import ymusic.ytmedia.ump.UmpPart
import ymusic.ytmedia.ump.copyFully
import ymusic.ytmedia.ump.readBytesFully
import ymusic.ytmedia.ump.readUmpPartHeader
import ymusic.ytmedia.ump.skipFully
import kotlin.random.Random
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
    ) {
        val startMark = TimeSource.Monotonic.markNow()
        var url = spec.sabrUrl.setQueryParam("alr", "yes")

        fun emitProgress() = progress?.onProgress(
            audio.file.length(), audio.format.clen,
            video?.file?.length() ?: 0, video?.format?.clen ?: 0,
        )
        emitProgress()
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
                    outcome = BufferedAsyncSource(response.body.asyncSource()).use { readResponse(it, audio, video) }
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
            emitProgress()
            if (isComplete(audio, video)) return

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
    internal suspend fun readResponse(body: BufferedAsyncSource, audio: SabrTrack, video: SabrTrack?): RoundOutcome {
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
                        check(!open.containsKey(header.headerId)) { "duplicate UMP header id ${header.headerId}" }
                        headers++
                        minSeg = minOf(minSeg, header.segmentIndex)
                        maxSeg = maxOf(maxSeg, header.segmentIndex)
                        val file = when (header.cacheKey) {
                            audio.format.cacheKey -> audio.file
                            video?.format?.cacheKey -> video.file
                            else -> throw IOException("unexpected media header ${header.videoId} ${header.cacheKey} seg ${header.segmentIndex}")
                        }
                        open[header.headerId] = PartState(header, file)
                    }

                    UmpPart.MEDIA_DATA -> {
                        val headerId = body.readByte().toInt() and 0xFF
                        val state = checkNotNull(open[headerId]) { "media data for unknown header $headerId" }
                        val dataSize = partSize - 1
                        writeMediaData(body, state, dataSize)
                        state.totalRead += dataSize
                    }

                    UmpPart.MEDIA_END -> {
                        val headerId = body.readByte().toInt() and 0xFF
                        val state = checkNotNull(open.remove(headerId)) { "media end for unknown header $headerId" }
                        state.sink?.close()
                        state.sink = null
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
            open.values.forEach { it.sink?.let { s -> runCatching { s.close() } } }
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
            val sink = state.file.appendSink()
            state.sink = sink
            body.copyFully(sink, dataSize - skips)
        } else {
            if (fileEnd < partStart) throw IOException("gap in download stream at ${state.header.itag}")
            body.skipFully(dataSize) // already have these bytes
        }
    }

    /** Ensure a length-delimited part is buffered, then parse its fields in place (no slice copy). */
    private suspend fun BufferedAsyncSource.readFields(size: Long): List<ProtoField> {
        require(size)
        return ProtoReader(buffer, size).readAllFields()
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

    private fun isComplete(audio: SabrTrack, video: SabrTrack?): Boolean =
        audio.file.length() >= audio.format.clen &&
            (video == null || video.file.length() >= video.format.clen)

    private companion object {
        private val PROTOBUF = "application/x-protobuf".toMediaTypeOrNull()
    }
}
