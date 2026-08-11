package io.ymusic.dynamicengine

import kotlinx.serialization.Serializable

/**
 * The wire shape of a boundary error — the single **typed** contract for the `{code, …}` object that
 * crosses Kotlin ↔ JS ↔ server (HOST_PROTOCOLS "Errors across the boundary"). Both the JS
 * `commandHandler` classifier and [io.ymusic.dynamicengine.internal.installHttp]'s `HttpProtocol` emit
 * exactly this shape; [decodeEngineError] reads it back into a typed [EngineError]. Defining it once
 * as a data type is why no tier pattern-matches the reason string.
 *
 * [code] is the only required field (a category or a site code); the rest are optional and omitted
 * when absent (`encodeDefaults` off).
 */
@Serializable
internal data class WireError(
    val code: String,
    val message: String? = null,
    /** JS constructor name (`TypeError`, `JsEngineError`, …) when the failure originated in JS. */
    val name: String? = null,
    /** Localized, display-ready text minted with the error; never technical detail. */
    val userMessage: String? = null,
    /** JS stack (capped at the boundary), for reports. */
    val stack: String? = null,
    val cause: Cause? = null,
) {
    @Serializable
    internal data class Cause(val name: String? = null, val message: String? = null)
}
