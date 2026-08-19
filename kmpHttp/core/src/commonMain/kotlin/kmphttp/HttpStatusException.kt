package kmphttp

/**
 * The server answered and refused: a non-2xx [code]. Deliberately NOT an [okio.IOException] — an
 * IOException (SSL included) means the server was never reached or the connection died, which is
 * always worth retrying; a refusal is its own decision for the caller.
 */
class HttpStatusException(val code: Int, message: String = "HTTP $code") : Exception(message) {

    /** 5xx and 429 are the server's problem and may pass; the other 4xx are the caller's and will not. */
    val retryable: Boolean get() = code >= 500 || code == 429
}
