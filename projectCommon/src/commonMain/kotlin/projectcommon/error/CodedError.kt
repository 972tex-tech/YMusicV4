package projectcommon.error

/**
 * An error carrying a stable, boundary-crossing **code** (see AGENTS.md "Error Handling"): either a
 * **well-known category** the UI localizes (`"network"`, `"parse"`, …) or a **site code**
 * (`E12375` — a unique hardcoded literal at exactly one throw/classification site, answered by a
 * global search). The code survives every hop (Kotlin ↔ JS ↔ server) unchanged: tiers that receive
 * a coded error wrap it (cause chain), never re-mint or translate it.
 *
 * Rendering, at the UI edge only, in fixed precedence:
 * 1. known category → the app's localized message;
 * 2. [userMessage] present → show it verbatim, code appended;
 * 3. otherwise → generic "Something went wrong (<code>)".
 */
interface CodedError {
    /** A well-known category (`"network"`, …) or a site code (`E#####`). Never shown raw as prose. */
    val code: String

    /** Display-ready, already-localized text minted with the error (e.g. by the server, which knows
     *  the request locale) — lets a failure mode invented after this app shipped explain itself.
     *  Never technical detail. */
    val userMessage: String?
}

/** A technical failure with a site code. [message] is for logs/reports — the user sees only the
 *  generic message plus [code] (or [userMessage] when provided). */
class TechnicalException(
    override val code: String,
    message: String,
    override val userMessage: String? = null,
    cause: Throwable? = null,
) : Exception("[$code] $message", cause), CodedError

/**
 * The deepest [CodedError] in the cause chain — the code closest to the root cause is the most
 * specific grep target, and outer wrappers must not shadow it.
 */
fun Throwable.codedError(): CodedError? {
    var deepest: CodedError? = null
    var t: Throwable? = this
    var depth = 0 // cause chains can be cyclic on some platforms
    while (t != null && depth++ < 100) {
        if (t is CodedError) deepest = t
        t = t.cause
    }
    return deepest
}

fun Throwable.errorCode(): String? = codedError()?.code
