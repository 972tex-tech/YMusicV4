package io.ymusic.ui.sponsorblock

import io.ymusic.remoteconfig.ApiService

/** The skip-segment fetch seam; [ServiceSponsorBlockApi] in the app. Times are seconds. */
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

/** Over the YMusic service's `/v1/sponsorblock/segments`. */
class ServiceSponsorBlockApi(private val api: ApiService) : SponsorBlockApi {
    override suspend fun getSkipSegments(videoId: String): GetSkipSegmentsResponse =
        GetSkipSegmentsResponse(
            api.getSkipSegments(videoId).map {
                SponsorBlockSegment(it.startTime, it.endTime, it.category, it.videoDuration)
            },
        )
}
