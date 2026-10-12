package io.ymusic.remoteconfig

import kotlinx.serialization.json.JsonObject
import projectcommon.json.double
import projectcommon.json.doubleOrNull
import projectcommon.json.string

/** One span `/v1/sponsorblock/segments` says to skip, in seconds; the service already merged and filtered them. */
data class SkipSegment(
    val startTime: Double,
    val endTime: Double,
    val category: String,
    val videoDuration: Double,
) {
    companion object {
        fun from(json: JsonObject): SkipSegment = SkipSegment(
            startTime = json.double("startTime"),
            endTime = json.double("endTime"),
            category = json.string("category"),
            videoDuration = json.doubleOrNull("videoDuration") ?: 0.0,
        )
    }
}
