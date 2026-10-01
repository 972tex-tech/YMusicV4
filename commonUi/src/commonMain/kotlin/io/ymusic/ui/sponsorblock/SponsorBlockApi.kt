package io.ymusic.ui.sponsorblock

/**
 * The skip-segment fetch seam. Wire shape matches the legacy `/sponsorBlock/getSkipSegments`
 * response (`startTime`/`endTime` in seconds). [MockSponsorBlockApi] drives the skip loop until
 * the real HTTP client is plugged in.
 */
interface SponsorBlockApi {
    suspend fun getSkipSegments(videoId: String): GetSkipSegmentsResponse
}

data class GetSkipSegmentsResponse(
    val segments: List<SponsorBlockSegment>,
)

data class SponsorBlockSegment(
    val startTime: Double,
    val endTime: Double,
    val category: String,
    val videoDuration: Double,
)

/** Scripted segments so any YouTube item exercises the skip loop. */
class MockSponsorBlockApi : SponsorBlockApi {
    override suspend fun getSkipSegments(videoId: String): GetSkipSegmentsResponse =
        GetSkipSegmentsResponse(
            segments = listOf(
                SponsorBlockSegment(
                    startTime = 0.0,
                    endTime = 10.0,
                    category = "sponsor",
                    videoDuration = 180.0,
                ),
                SponsorBlockSegment(
                    startTime = 60.0,
                    endTime = 75.0,
                    category = "sponsor",
                    videoDuration = 180.0,
                ),
            ),
        )
}
