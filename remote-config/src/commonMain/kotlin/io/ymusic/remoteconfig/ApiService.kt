package io.ymusic.remoteconfig

/** WIRE CONTRACT: one [ApiService.pulse] carries at most this many events; the service 413s more. */
const val PULSE_MAX_EVENTS = 15

/**
 * The service, as the rest of the app is allowed to see it: named calls, typed answers. Everything
 * about *reaching* it — the url, the common query params, keys, headers — lives behind this
 * interface, so no other class knows an endpoint exists.
 *
 * Failure is thrown, not returned: [kmphttp.HttpStatusException] when the server answered and
 * refused, [okio.IOException] (SSL included) when it was never reached or the connection died, and
 * whatever the parse throws when the body is not what it should be. Cancellation passes through
 * untouched. Nothing here retries or caches; that is the caller's policy ([RemoteConfig]'s, today).
 */
interface ApiService {

    /** The current `/config` document. Never a partial answer — see the failure contract above. */
    suspend fun getConfig(): RemoteConfigData

    /**
     * Deliver usage events in one call, all-or-nothing; same failure contract as [getConfig].
     * WIRE CONTRACT: at most [PULSE_MAX_EVENTS] events per call (more throws
     * [IllegalArgumentException] before any request leaves), ordered oldest to newest — the
     * service keeps a per-device "last credited day" watermark that only moves forward, so
     * out-of-order days would silently lose their activity credit.
     */
    suspend fun pulse(events: List<PulseEvent>)

    /** QR sign-in relay: a fresh one-shot slot, open for ten minutes. Its id goes into the QR. */
    suspend fun openDeviceLink(): String

    /** Fill slot [id] with [payload] (base64url). False when there is no open slot: expired or used. */
    suspend fun putDeviceLink(id: String, payload: String): Boolean

    /**
     * Slot [id]'s payload once the other device filled it, null while it is still empty. Reading it
     * deletes it. Throws [kmphttp.HttpStatusException] 404 once the slot is gone (expired or taken).
     */
    suspend fun takeDeviceLink(id: String): String?

    /** Give slot [id] up. */
    suspend fun closeDeviceLink(id: String)

    /**
     * The SponsorBlock spans to skip in YouTube video [videoId], sorted; empty when it has none.
     * Throws [kmphttp.HttpStatusException] 503 while the service has no SponsorBlock data yet.
     */
    suspend fun getSkipSegments(videoId: String): List<SkipSegment>
}
