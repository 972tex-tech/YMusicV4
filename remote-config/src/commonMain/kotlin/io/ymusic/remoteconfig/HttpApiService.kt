package io.ymusic.remoteconfig

import io.ymusic.remoteconfig.PulseEvent.Companion.value
import kmpcommon.io.buffer
import kmphttp.HttpStatusException
import kmphttp.HttpUrl
import kmphttp.HttpUrlBuilder
import kmphttp.KmpHttpClient
import kmphttp.MediaTypeX.toMediaType
import kmphttp.RequestBodyX.asOneshotRequestBody
import kmphttp.RequestBuilder
import kmphttp.asyncSource
import kmphttp.json.JsonReader
import kmphttp.json.JsonWriter
import kmphttp.toHttpUrlOrNull
import okio.use

/**
 * The real [ApiService], over [KmpHttpClient]. Holds everything request-shaped: the base url and
 * what every call carries — os, app version, the anonymous device uuid and the api key, all as
 * headers — so a caller gets a typed answer or a thrown failure, never a url or a status code to
 * interpret.
 *
 * Only construct with a non-blank [baseUrl]: a build without a service passes null [ApiService]
 * around instead. A [baseUrl] that is not a url throws from every call — a packaging mistake worth
 * hearing about, not a request worth sending.
 */
class HttpApiService(
    private val client: KmpHttpClient,
    /** Coarse platform name the service keys releases by: windows, macos, linux, android, ios. */
    private val os: String,
    /** The platform's own version string: Android release ("15"), macOS/Windows version, etc. */
    private val osVersion: String,
    private val appVersionCode: Int,
    private val deviceId: String,
    private val baseUrl: String,
    private val now: () -> Long,
    private val apiKey: String? = null,
) : ApiService {

    override suspend fun getConfig(): RemoteConfigData {
        val url = url().addPathSegment("v1").addPathSegment("config").build()
        return client.execute(request(url).get().build()).use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            // Parsed straight off the socket — the body is never held whole. [now] stamps
            // updatedAt at the moment of parse; see RemoteConfigData.from for who may override it.
            RemoteConfigData.from(JsonReader(response.body.asyncSource().buffer()).readObject(), now)
        }
    }

    override suspend fun pulse(events: List<PulseEvent>) {
        require(events.size <= PULSE_MAX_EVENTS) {
            "pulse carries at most $PULSE_MAX_EVENTS events, got ${events.size} — chunk the queue"
        }
        val writer = JsonWriter.Sync()
        with(writer) {
            beginObject()
            name("events").beginArray()
            events.forEach { value(it) }
            endArray()
            endObject()
        }
        val url = url().addPathSegment("v1").addPathSegment("pulse").build()
        val body = writer.buffer.asOneshotRequestBody("application/json".toMediaType())
        client.execute(request(url).post(body).build()).use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
        }
    }

    private fun url(): HttpUrlBuilder =
        requireNotNull(baseUrl.toHttpUrlOrNull()) { "not a url: $baseUrl" }.newBuilder()

    /** Every call goes out with the common headers; a new endpoint only picks its url and verb. */
    private fun request(url: HttpUrl): RequestBuilder {
        val request = RequestBuilder().url(url)
            .header(HEADER_OS, os)
            .header(HEADER_OS_VER, osVersion)
            .header(HEADER_APP_VER, appVersionCode.toString())
            .header(HEADER_DEVICE_ID, deviceId)
        apiKey?.let { request.header(HEADER_API_KEY, it) }
        return request
    }

    companion object {
        const val HEADER_OS = "x-os"
        const val HEADER_OS_VER = "x-os-ver"
        const val HEADER_APP_VER = "x-app-ver"
        const val HEADER_DEVICE_ID = "x-device-id"
        const val HEADER_API_KEY = "x-api-key"
    }
}
