package kmphttp.cache

import kmphttp.HttpStatus.HTTP_BAD_METHOD
import kmphttp.HttpStatus.HTTP_GONE
import kmphttp.HttpStatus.HTTP_MOVED_PERM
import kmphttp.HttpStatus.HTTP_MOVED_TEMP
import kmphttp.HttpStatus.HTTP_MULT_CHOICE
import kmphttp.HttpStatus.HTTP_NOT_AUTHORITATIVE
import kmphttp.HttpStatus.HTTP_NOT_FOUND
import kmphttp.HttpStatus.HTTP_NOT_IMPLEMENTED
import kmphttp.HttpStatus.HTTP_NO_CONTENT
import kmphttp.HttpStatus.HTTP_OK
import kmphttp.HttpStatus.HTTP_PERM_REDIRECT
import kmphttp.HttpStatus.HTTP_REQ_TOO_LONG
import kmphttp.HttpStatus.HTTP_TEMP_REDIRECT
import kmphttp.Request
import kmphttp.Response
import kmphttp.header
import kmphttp.internal.toHttpDateOrNull
import kmphttp.toNonNegativeInt
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Given a request and cached response, this figures out whether to use the network, the cache, or
 * both.
 *
 * Selecting a cache strategy may add conditions to the request (like the "If-Modified-Since" header
 * for conditional GETs) or warnings to the cached response (if the cached data is potentially
 * stale).
 */
internal class CacheStrategy internal constructor(
    /** The request to send on the network, or null if this call doesn't use the network. */
    val networkRequest: Request?,
    /** The cached response to return or validate; or null if this call doesn't use a cache. */
    val cacheResponse: Response?,
) {
    @OptIn(ExperimentalTime::class)
    class Factory(
        private val nowMillis: Long,
        internal val request: Request,
        private val cacheResponse: Response?,
    ) {
        /** The server's time when the cached response was served, if known. */
        private var servedDate: Instant? = null
        private var servedDateString: String? = null

        /** The last modified date of the cached response, if known. */
        private var lastModified: Instant? = null
        private var lastModifiedString: String? = null

        /**
         * The expiration date of the cached response, if known. If both this field and the max age are
         * set, the max age is preferred.
         */
        private var expires: Instant? = null

        /**
         * Extension header set by OkHttp specifying the timestamp when the cached HTTP request was
         * first initiated.
         */
        private var sentRequestMillis = 0L

        /**
         * Extension header set by OkHttp specifying the timestamp when the cached HTTP response was
         * first received.
         */
        private var receivedResponseMillis = 0L

        /** Etag of the cached response. */
        private var etag: String? = null

        /** Age of the cached response. */
        private var ageSeconds = -1

        /**
         * Returns true if computeFreshnessLifetime used a heuristic. If we used a heuristic to serve a
         * cached response older than 24 hours, we are required to attach a warning.
         */
        private fun isFreshnessLifetimeHeuristic(): Boolean =
            cacheResponse!!.cacheControl.maxAgeSeconds == -1 && expires == null

        init {
            if (cacheResponse != null) {
                this.sentRequestMillis = cacheResponse.sentRequestAtMillis
                this.receivedResponseMillis = cacheResponse.receivedResponseAtMillis
                val headers = cacheResponse.headers
                for (i in 0 until headers.size) {
                    val fieldName = headers.name(i)
                    val value = headers.value(i)
                    when {
                        fieldName.equals("Date", ignoreCase = true) -> {
                            servedDate = value.toHttpDateOrNull()
                            servedDateString = value
                        }

                        fieldName.equals("Expires", ignoreCase = true) -> {
                            expires = value.toHttpDateOrNull()
                        }

                        fieldName.equals("Last-Modified", ignoreCase = true) -> {
                            lastModified = value.toHttpDateOrNull()
                            lastModifiedString = value
                        }

                        fieldName.equals("ETag", ignoreCase = true) -> {
                            etag = value
                        }

                        fieldName.equals("Age", ignoreCase = true) -> {
                            ageSeconds = value.toNonNegativeInt(-1)
                        }
                    }
                }
            }
        }

        /** Returns a strategy to satisfy [request] using [cacheResponse]. */
        fun compute(): CacheStrategy {
            val candidate = computeCandidate()

            // We're forbidden from using the network and the cache is insufficient.
            if (candidate.networkRequest != null && request.cacheControl.onlyIfCached) {
                return CacheStrategy(null, null)
            }

            return candidate
        }

        /** Returns a strategy to use assuming the request can use the network. */
        private fun computeCandidate(): CacheStrategy {
            // No cached response.
            if (cacheResponse == null) {
                return CacheStrategy(request, null)
            }

            // Drop the cached response if it's missing a required handshake.
//      if (request.isHttps && cacheResponse.handshake == null) {
//        return CacheStrategy(request, null)
//      }

            // If this response shouldn't have been stored, it should never be used as a response source.
            // This check should be redundant as long as the persistence store is well-behaved and the
            // rules are constant.
            if (!isCacheable(cacheResponse, request)) {
                return CacheStrategy(request, null)
            }

            val requestCaching = request.cacheControl
            if (requestCaching.noCache || hasConditions(request)) {
                return CacheStrategy(request, null)
            }

            val responseCaching = cacheResponse.cacheControl

            val ageMillis = cacheResponseAge()
            var freshMillis = computeFreshnessLifetime()

            if (requestCaching.maxAgeSeconds != -1) {
                freshMillis =
                    minOf(freshMillis, requestCaching.maxAgeSeconds.seconds.inWholeMilliseconds)
            }

            var minFreshMillis: Long = 0
            if (requestCaching.minFreshSeconds != -1) {
                minFreshMillis = requestCaching.minFreshSeconds.seconds.inWholeMilliseconds
            }

            var maxStaleMillis: Long = 0
            if (!responseCaching.mustRevalidate && requestCaching.maxStaleSeconds != -1) {
                maxStaleMillis = requestCaching.maxStaleSeconds.seconds.inWholeMilliseconds
            }

            if (!responseCaching.noCache && ageMillis + minFreshMillis < freshMillis + maxStaleMillis) {
                val builder = cacheResponse.newBuilder()
                if (ageMillis + minFreshMillis >= freshMillis) {
                    builder.addHeader("Warning", "110 HttpURLConnection \"Response is stale\"")
                }
                val oneDayMillis = 24 * 60 * 60 * 1000L
                if (ageMillis > oneDayMillis && isFreshnessLifetimeHeuristic()) {
                    builder.addHeader("Warning", "113 HttpURLConnection \"Heuristic expiration\"")
                }
                return CacheStrategy(null, builder.build())
            }

            // Find a condition to add to the request. If the condition is satisfied, the response body
            // will not be transmitted.
            val conditionName: String
            val conditionValue: String?
            when {
                etag != null -> {
                    conditionName = "If-None-Match"
                    conditionValue = etag
                }

                lastModified != null -> {
                    conditionName = "If-Modified-Since"
                    conditionValue = lastModifiedString
                }

                servedDate != null -> {
                    conditionName = "If-Modified-Since"
                    conditionValue = servedDateString
                }

                else -> return CacheStrategy(request, null) // No condition! Make a regular request.
            }

            val conditionalRequestHeaders = request.headers.newBuilder()
            conditionalRequestHeaders.addLenient(conditionName, conditionValue!!)

            val conditionalRequest =
                request
                    .newBuilder()
                    .headers(conditionalRequestHeaders.build())
                    .build()
            return CacheStrategy(conditionalRequest, cacheResponse)
        }

        /**
         * Returns the number of milliseconds that the response was fresh for, starting from the served
         * date.
         */
        private fun computeFreshnessLifetime(): Long {
            val responseCaching = cacheResponse!!.cacheControl
            if (responseCaching.maxAgeSeconds != -1) {
                return responseCaching.maxAgeSeconds.seconds.inWholeMilliseconds
            }

            val expires = this.expires
            if (expires != null) {
                val servedMillis = servedDate?.toEpochMilliseconds() ?: receivedResponseMillis
                val delta = expires.toEpochMilliseconds() - servedMillis
                return if (delta > 0L) delta else 0L
            }

            if (lastModified != null && cacheResponse.request.url.encodedQuery == null) {
                // As recommended by the HTTP RFC and implemented in Firefox, the max age of a document
                // should be defaulted to 10% of the document's age at the time it was served. Default
                // expiration dates aren't used for URIs containing a query.
                val servedMillis = servedDate?.toEpochMilliseconds() ?: sentRequestMillis
                val delta = servedMillis - lastModified!!.toEpochMilliseconds()
                return if (delta > 0L) delta / 10 else 0L
            }

            return 0L
        }

        /**
         * Returns the current age of the response, in milliseconds. The calculation is specified by RFC
         * 7234, 4.2.3 Calculating Age.
         */
        private fun cacheResponseAge(): Long {
            val servedDate = this.servedDate
            val apparentReceivedAge =
                if (servedDate != null) {
                    maxOf(0, receivedResponseMillis - servedDate.toEpochMilliseconds())
                } else {
                    0
                }

            val receivedAge =
                if (ageSeconds != -1) {
                    maxOf(apparentReceivedAge, ageSeconds.seconds.inWholeMilliseconds)
                } else {
                    apparentReceivedAge
                }

            val responseDuration = maxOf(0, receivedResponseMillis - sentRequestMillis)
            val residentDuration = maxOf(0, nowMillis - receivedResponseMillis)
            return receivedAge + responseDuration + residentDuration
        }

        /**
         * Returns true if the request contains conditions that save the server from sending a response
         * that the client has locally. When a request is enqueued with its own conditions, the built-in
         * response cache won't be used.
         */
        private fun hasConditions(request: Request): Boolean =
            request.header("If-Modified-Since") != null || request.header("If-None-Match") != null
    }

    companion object {
        /** Returns true if [response] can be stored to later serve another request. */
        fun isCacheable(
            response: Response,
            request: Request,
        ): Boolean {
            // Always go to network for uncacheable response codes (RFC 7231 section 6.1), This
            // implementation doesn't support caching partial content.

            when (response.code) {
                HTTP_OK,
                HTTP_NOT_AUTHORITATIVE,
                HTTP_NO_CONTENT,
                HTTP_MULT_CHOICE,
                HTTP_MOVED_PERM,
                HTTP_NOT_FOUND,
                HTTP_BAD_METHOD,
                HTTP_GONE,
                HTTP_REQ_TOO_LONG,
                HTTP_NOT_IMPLEMENTED,
                HTTP_PERM_REDIRECT,
                -> {
                    // These codes can be cached unless headers forbid it.
                }

                HTTP_MOVED_TEMP,
                HTTP_TEMP_REDIRECT,
                -> {
                    // These codes can only be cached with the right response headers.
                    // http://tools.ietf.org/html/rfc7234#section-3
                    // s-maxage is not checked because OkHttp is a private cache that should ignore s-maxage.
                    if (response.header("Expires") == null &&
                        response.cacheControl.maxAgeSeconds == -1 &&
                        !response.cacheControl.isPublic &&
                        !response.cacheControl.isPrivate
                    ) {
                        return false
                    }
                }

                else -> {
                    // All other codes cannot be cached.
                    return false
                }
            }

            // A 'no-store' directive on request or response prevents the response from being cached.
            return !response.cacheControl.noStore && !request.cacheControl.noStore
        }
    }
}
