package ymusic.dynamicengine

import kmpcommon.error.CodedError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A failure of an engine call, rebuilt from the structured RESET reason the JS `serve()` dispatcher
 * sends (HOST_PROTOCOLS "Errors across the boundary"): `{code, name, message, stack, userMessage}`.
 *
 * [code] is the boundary contract — a well-known category mapped to a subclass here, or a site
 * code (`E#####`, one hardcoded literal at one throw site, in any tier: app, remote JS, server).
 * The taxonomy is OPEN: remote JS and the server update independently of this app build, so a
 * category minted after this build lands as [Other] with the code intact — never a crash.
 */
sealed class EngineError(
    override val code: String,
    message: String,
    override val userMessage: String?,
    /** JS constructor name (`TypeError`, `ExtractError`, …) when the failure originated in JS. */
    val jsName: String?,
    /** JS stack (capped at the boundary), for reports. */
    val jsStack: String?,
    cause: Throwable?,
) : Exception(message, cause), CodedError {

    /** Transport failure (DNS/connect/TLS/timeout) — retryable, "check your connection". */
    class Network internal constructor(m: String, u: String?, n: String?, s: String?, c: Throwable?) :
        EngineError("network", m, u, n, s, c)

    /** The scraped data's shape wasn't understood — the signal to ship updated engine JS. */
    class Parse internal constructor(m: String, u: String?, n: String?, s: String?, c: Throwable?) :
        EngineError("parse", m, u, n, s, c)

    /** The JS engine itself gave out (OOM, stack overflow) — when it was even catchable. */
    class Oom internal constructor(m: String, u: String?, n: String?, s: String?, c: Throwable?) :
        EngineError("oom", m, u, n, s, c)

    /** A site-coded technical failure (`E#####`) from any tier — rendered as the generic
     *  something-went-wrong message plus the code. */
    class Technical internal constructor(code: String, m: String, u: String?, n: String?, s: String?, c: Throwable?) :
        EngineError(code, m, u, n, s, c)

    /** A category this app build doesn't know (newer remote JS or server). The code is preserved
     *  for display ("… (region_blocked)") and telemetry. */
    class Other internal constructor(code: String, m: String, u: String?, n: String?, s: String?, c: Throwable?) :
        EngineError(code, m, u, n, s, c)
}

private val SITE_CODE = Regex("""E\d{5}""")

/** Rebuild an [EngineError] from a pipe RESET [reason]. Fallback-tolerant: a reason that isn't the
 *  structured JSON object (transport-generated, or a raw `ymix.onAccept` handler's backstop
 *  message) still salvages an embedded site code, else mints the decoder's own. */
internal fun decodeEngineError(reason: String, cause: Throwable?): EngineError {
    val obj = reason.takeIf { it.startsWith("{") }?.let {
        try {
            Json.parseToJsonElement(it) as? JsonObject
        } catch (_: Exception) {
            null
        }
    }
    val code = obj?.get("code")?.jsonPrimitive?.contentOrNull
    if (obj == null || code == null) {
        val embedded = SITE_CODE.find(reason)?.value
        return EngineError.Technical(embedded ?: "E20003", reason, null, null, null, cause) // E20003: unstructured pipe reset
    }
    val message = obj["message"]?.jsonPrimitive?.contentOrNull ?: reason
    val userMessage = obj["userMessage"]?.jsonPrimitive?.contentOrNull
    val name = obj["name"]?.jsonPrimitive?.contentOrNull
    val stack = obj["stack"]?.jsonPrimitive?.contentOrNull
    return when {
        code == "network" -> EngineError.Network(message, userMessage, name, stack, cause)
        code == "parse" -> EngineError.Parse(message, userMessage, name, stack, cause)
        code == "oom" -> EngineError.Oom(message, userMessage, name, stack, cause)
        SITE_CODE.matches(code) -> EngineError.Technical(code, message, userMessage, name, stack, cause)
        else -> EngineError.Other(code, message, userMessage, name, stack, cause)
    }
}
