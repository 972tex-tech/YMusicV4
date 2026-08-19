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
}
