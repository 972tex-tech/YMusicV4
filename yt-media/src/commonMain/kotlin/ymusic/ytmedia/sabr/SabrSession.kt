package ymusic.ytmedia.sabr

import kotlinx.coroutines.delay
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.IOException
import ymusic.ytmedia.proto.ProtoReader
import ymusic.ytmedia.ump.UmpPart
import ymusic.ytmedia.ump.readUmpPartHeader
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
 * `SabrDownloader.sabrDownloader`. Pure protocol: HTTP is injected ([SabrHttp]), byte sinks are
 * [DownloadFile]s. Drives one or two format streams (audio + optional video) to completion.
 */
class SabrSession(
    private val http: SabrHttp,
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

    suspend fun download(
        spec: SabrStreamSpec,
        audio: SabrTrack,
        video: SabrTrack? = null,
        progress: SabrProgress? = null,
    ) {
        val startMark = TimeSource.Monotonic.markNow()
        var url = spec.sabrUrl.setQueryParam("alr", "yes")

        // Last completed segment per track (drives buffered ranges + resume within this session).
        var audioSegment: MediaHeader? = null
        var videoSegment: MediaHeader? = null

        fun emitProgress() = progress?.onProgress(
            audio.file.length(), audio.format.clen,
            video?.file?.length() ?: 0, video?.format?.clen ?: 0,
        )
        emitProgress()
        if (isComplete(audio, video)) return

        // A video format must be offered even when downloading audio-only, or the request fails.
        val fallbackVideoFormat = video?.format
            ?: spec.formats.lastOrNull { it.isVideo }

        var retryCount = 0
        var lastError: String? = null

        while (true) {
            if (retryCount > maxConsecutiveEmptyRounds) {
                if (!poTokenValid) throw SabrPoTokenExpired("PO token rejected; re-attest and retry")
                throw IOException("SABR exceeded max retries, last: $lastError")
            }

            val audioBuffered = bufferedRange(audioSegment)
            val videoBuffered = if (video != null) bufferedRange(videoSegment) else null
            var playerTimeMs = audioBuffered?.let { it.endMs + 1 } ?: 0
            if (video != null) {
                playerTimeMs = minOf(playerTimeMs, videoBuffered?.let { it.endMs + 1 } ?: 0)
            }

            val timeSinceLastSeek = random.nextInt(50) + 1 // must be > 0
            val request = VideoPlaybackAbrRequest(
                clientAbrState = ClientAbrState(
                    playerTimeMs = playerTimeMs,
                    enabledTrackTypesBitfield = if (video != null) 0 else 1,
                    drcEnabled = audio.format.isDrc,
                    viewportWidth = viewportWidth,
                    viewportHeight = viewportHeight,
                    timeSinceLastSeek = timeSinceLastSeek,
                    visibility = 0,
                    elapsedWallTimeMs = startMark.elapsedNow().inWholeMilliseconds.toInt(),
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

            val requestUrl = url
                .setQueryParam("cpn", cpn)
                .setQueryParam("rn", (++rn).toString())

            log(
                "round rn=$rn playerTime=$playerTimeMs audioSeg=${audioSegment?.segmentIndex} " +
                    "videoSeg=${videoSegment?.segmentIndex} bufferedEnd=${audioBuffered?.endMs} " +
                    "selected=${request.selectedFormats.size} cookieLen=${playbackCookie.size}",
            )
            // Seed the round's segment trackers from the session so contiguity is checked against the
            // last segment we actually have (segment N+1 continues N across request boundaries).
            val round = RoundState()
            round.audioSegment = audioSegment
            round.videoSegment = videoSegment
            val response = http.post(
                requestUrl,
                mapOf("content-type" to "application/x-protobuf"),
                request.encode().toByteArray(),
            )
            try {
                if (response.isRedirect) {
                    val location = response.location
                    if (location == null || !location.contains("/videoplayback")) {
                        throw IOException("Invalid SABR redirect: $location")
                    }
                    url = location
                    continue
                }
                if (!response.isSuccessful) throw IOException("SABR response ${response.status}")
                val redirect = parseUmpStream(response.body, audio, video, round)
                log(
                    "  resp headers=${round.headers} ends=${round.mediaEnds} " +
                        "seg=[${if (round.headers > 0) "${round.minSeg}..${round.maxSeg}" else "-"}] " +
                        "completed=${round.completedSegments} backoffMs=${round.backoffMs} " +
                        "protection=${round.protectionStatus} audioBytes=${audio.file.length()}",
                )
                if (redirect != null) {
                    url = redirect
                    continue
                }
            } finally {
                response.close()
                round.closeOpenSinks()
            }

            audioSegment = round.audioSegment ?: audioSegment
            videoSegment = round.videoSegment ?: videoSegment
            emitProgress()
            if (isComplete(audio, video)) return

            if (round.completedSegments == 0) {
                retryCount++
                lastError = "received zero segments"
                delay(maxOf(round.backoffMs, 2000).toLong())
            } else {
                retryCount = 0
            }
        }
    }

    /** Per-request mutable state; segments in flight keyed by UMP headerId. */
    private inner class RoundState {
        val parts = mutableMapOf<Int, PartState>()
        var backoffMs = 0
        var completedSegments = 0
        var audioSegment: MediaHeader? = null
        var videoSegment: MediaHeader? = null
        // diagnostics
        var headers = 0
        var mediaEnds = 0
        var minSeg = Int.MAX_VALUE
        var maxSeg = Int.MIN_VALUE
        var protectionStatus: Int? = null
        var redirected = false
        fun closeOpenSinks() {
            parts.values.forEach { it.sink?.let { s -> runCatching { s.close() } } }
            parts.clear()
        }
    }

    private class PartState(val header: MediaHeader, val file: DownloadFile) {
        var sink: BufferedSink? = null
        var totalRead: Long = 0
    }

    /** Returns a redirect URL if the stream carried a SABR_REDIRECT (part 43), else null. */
    private fun parseUmpStream(
        body: BufferedSource,
        audio: SabrTrack,
        video: SabrTrack?,
        round: RoundState,
    ): String? {
        while (!body.exhausted()) {
            val (partId, partSize) = body.readUmpPartHeader()
            when (partId) {
                UmpPart.SABR_ERROR ->
                    throw SabrServerError("SABR error: ${SabrParts.parseErrorMessage(body.readByteArray(partSize))}")

                UmpPart.SABR_REDIRECT -> {
                    val redirect = SabrParts.parseRedirectUrl(body.readByteArray(partSize))
                    if (redirect == null || !redirect.contains("/videoplayback")) {
                        throw IOException("Invalid SABR redirect part: $redirect")
                    }
                    return redirect
                }

                UmpPart.MEDIA_HEADER -> {
                    val header = MediaHeader.from(ProtoReader(Buffer().write(body.readByteArray(partSize))).readAllFields())
                    check(!round.parts.containsKey(header.headerId)) { "duplicate UMP header id ${header.headerId}" }
                    round.headers++
                    round.minSeg = minOf(round.minSeg, header.segmentIndex)
                    round.maxSeg = maxOf(round.maxSeg, header.segmentIndex)
                    val file = when (header.cacheKey) {
                        audio.format.cacheKey -> audio.file
                        video?.format?.cacheKey -> video.file
                        else -> throw IOException("unexpected media header ${header.videoId} ${header.cacheKey} seg ${header.segmentIndex}")
                    }
                    round.parts[header.headerId] = PartState(header, file)
                }

                UmpPart.MEDIA_DATA -> {
                    val headerId = body.readByte().toInt() and 0xFF
                    val state = checkNotNull(round.parts[headerId]) { "media data for unknown header $headerId" }
                    val dataSize = partSize - 1
                    writeMediaData(body, state, dataSize)
                    state.totalRead += dataSize
                }

                UmpPart.MEDIA_END -> {
                    val headerId = body.readByte().toInt() and 0xFF
                    val state = checkNotNull(round.parts.remove(headerId)) { "media end for unknown header $headerId" }
                    state.sink?.close()
                    state.sink = null
                    round.mediaEnds++
                    advanceIfContiguous(state.header, audio, video, round)
                }

                UmpPart.NEXT_REQUEST_POLICY -> {
                    val policy = NextRequestPolicy.from(ProtoReader(Buffer().write(body.readByteArray(partSize))).readAllFields())
                    policy.playbackCookie?.let { playbackCookie = it }
                    round.backoffMs = policy.backoffTimeMs ?: 0
                }

                UmpPart.STREAM_PROTECTION_STATUS -> {
                    val status = SabrParts.parseStreamProtectionStatus(body.readByteArray(partSize))
                    round.protectionStatus = status
                    if (status != null) poTokenValid = status != 2 && status != 3
                }

                UmpPart.SABR_CONTEXT_UPDATE -> {
                    val update = runCatching {
                        SabrContextUpdate.fromUmpResponse(ProtoReader(Buffer().write(body.readByteArray(partSize))).readAllFields())
                    }.getOrNull()
                    if (update != null) sabrContextUpdates[update.type] = update
                }

                else -> body.skip(partSize)
            }
        }
        return null
    }

    /**
     * Write one MEDIA_DATA chunk, skipping any prefix the file already has (server may resend bytes
     * inside our buffered range). Mirrors legacy's byte-position reconciliation.
     */
    private fun writeMediaData(body: BufferedSource, state: PartState, dataSize: Long) {
        state.sink?.let { sink ->
            body.readFully(sink, dataSize)
            return
        }
        val fileEnd = state.file.length()
        val partStart = state.header.startRange + state.totalRead
        val partEnd = partStart + dataSize
        if (fileEnd in partStart until partEnd) {
            val skips = fileEnd - partStart
            if (skips > 0) body.skip(skips)
            val sink = state.file.appendSink()
            state.sink = sink
            body.readFully(sink, dataSize - skips)
        } else {
            if (fileEnd < partStart) throw IOException("gap in download stream at ${state.header.itag}")
            body.skip(dataSize) // already have these bytes
        }
    }

    private fun advanceIfContiguous(header: MediaHeader, audio: SabrTrack, video: SabrTrack?, round: RoundState) {
        when (header.cacheKey) {
            audio.format.cacheKey ->
                if (header.segmentIndex - 1 == (round.audioSegment?.segmentIndex ?: -1)) {
                    round.audioSegment = header
                    round.completedSegments++
                }
            video?.format?.cacheKey ->
                if (header.segmentIndex - 1 == (round.videoSegment?.segmentIndex ?: -1)) {
                    round.videoSegment = header
                    round.completedSegments++
                }
        }
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
}

/** Reads exactly [count] bytes from [this] into [sink]. */
private fun BufferedSource.readFully(sink: BufferedSink, count: Long) {
    var remaining = count
    val buffer = Buffer()
    while (remaining > 0) {
        val read = read(buffer, minOf(remaining, 64 * 1024))
        if (read == -1L) throw IOException("unexpected end of UMP media data, $remaining bytes short")
        sink.write(buffer, read)
        remaining -= read
    }
    sink.flush()
}
