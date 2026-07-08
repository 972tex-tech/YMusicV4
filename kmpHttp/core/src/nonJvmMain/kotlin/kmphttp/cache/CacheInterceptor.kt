package kmphttp.cache

import kmpcommon.io.AsyncSource
import kmphttp.AsyncInterceptor
import kmphttp.AsyncInterceptor.Chain
import kmphttp.Headers
import kmphttp.HeadersBuilder
import kmphttp.HttpCache
import kmphttp.HttpCache.CacheRequest
import kmphttp.HttpStatus.HTTP_GATEWAY_TIMEOUT
import kmphttp.HttpStatus.HTTP_NOT_MODIFIED
import kmphttp.MediaType
import kmphttp.MediaTypeX.toMediaTypeOrNull
import kmphttp.Protocol
import kmphttp.Response
import kmphttp.ResponseBody
import kmphttp.ResponseBuilder
import kmphttp.header
import kmphttp.internal.HttpMethod
import kmphttp.internal.closeQuietly
import kmphttp.internal.stripBody
import kmphttp.promisesBody
import okio.Buffer
import okio.IOException
import kotlin.time.Clock.System
import kotlin.time.ExperimentalTime

/** Serves requests from the cache and writes responses to the cache. */
@OptIn(ExperimentalTime::class)
class CacheInterceptor(
    internal val cache: HttpCache?,
) : AsyncInterceptor {

    override suspend fun intercept(chain: Chain): Response {
        val cacheCandidate = cache?.get(chain.request)

        val now = System.now().toEpochMilliseconds()

        val strategy = CacheStrategy.Factory(now, chain.request, cacheCandidate).compute()
        val networkRequest = strategy.networkRequest
        val cacheResponse = strategy.cacheResponse

        if (cacheCandidate != null && cacheResponse == null) {
            // The cache candidate wasn't applicable. Close it.
            cacheCandidate.closeQuietly()
        }

        // If we're forbidden from using the network and the cache is insufficient, fail.
        if (networkRequest == null && cacheResponse == null) {
            return ResponseBuilder()
                .request(chain.request)
                .protocol(Protocol.HTTP_1_1)
                .code(HTTP_GATEWAY_TIMEOUT)
                .message("Unsatisfiable Request (only-if-cached)")
                .sentRequestAtMillis(-1L)
                .receivedResponseAtMillis(System.now().toEpochMilliseconds())
                .build()
        }

        // If we don't need the network, we're done.
        if (networkRequest == null) {
            return checkNotNull(cacheResponse)
                .newBuilder()
                .cacheResponse(cacheResponse.stripBody())
                .build()
        }

        var networkResponse: Response? = null
        try {
            networkResponse = chain.proceed(networkRequest)
        } finally {
            // If we're crashing on I/O or otherwise, don't leak the cache body.
            if (networkResponse == null && cacheCandidate != null) {
                cacheCandidate.body.closeQuietly()
            }
        }

        // If we have a cache response too, then we're doing a conditional get.
        if (cacheResponse != null) {
            if (networkResponse.code == HTTP_NOT_MODIFIED) {
                val response =
                    cacheResponse
                        .newBuilder()
                        .headers(combine(cacheResponse.headers, networkResponse.headers))
                        .sentRequestAtMillis(networkResponse.sentRequestAtMillis)
                        .receivedResponseAtMillis(networkResponse.receivedResponseAtMillis)
                        .cacheResponse(cacheResponse.stripBody())
                        .networkResponse(networkResponse.stripBody())
                        .build()

                networkResponse.body.closeQuietly()

                try {
                    // Update the cache after combining headers but before stripping the
                    // Content-Encoding header (as performed by initContentStream()).
                    checkNotNull(cache).update(cacheResponse, response)
                    return response
                } catch (e: Throwable) {
                    cacheResponse.closeQuietly()
                    response.closeQuietly()
                    throw e
                }
            } else {
                cacheResponse.body.closeQuietly()
            }
        }

        val response =
            networkResponse
                .newBuilder()
                .cacheResponse(cacheResponse?.stripBody())
                .networkResponse(networkResponse.stripBody())
                .build()

        if (cache != null) {
            val cacheNetworkRequest = networkRequest

            if (response.promisesBody() && CacheStrategy.isCacheable(
                    response,
                    cacheNetworkRequest
                )
            ) {
                try {
                    // Offer this request to the cache.
                    val cacheRequest =
                        cache.put(response.newBuilder().request(cacheNetworkRequest).build())
                    return cacheWritingResponse(cacheRequest, response)
                } catch (e: Throwable) {
                    response.closeQuietly()
                    throw e
                }
            }

            if (HttpMethod.invalidatesCache(networkRequest.method)) {
                try {
                    cache.remove(networkRequest)
                } catch (_: IOException) {
                    // The cache cannot be written.
                }
            }
        }

        return response
    }

    /**
     * Returns a new source that writes bytes to [cacheRequest] as they are read by the source
     * consumer. This is careful to discard bytes left over when the stream is closed; otherwise we
     * may never exhaust the source stream and therefore not complete the cached response.
     */
    @Throws(IOException::class)
    private fun cacheWritingResponse(
        cacheRequest: CacheRequest?,
        response: Response,
    ): Response {
        // Some apps return a null body; for compatibility we treat that like a null cache request.
        if (cacheRequest == null) return response

        val source = response.body.asyncSource()
        val cacheBody = cacheRequest.bodyWriter()

        val cacheWritingSource =
            object : AsyncSource {
                private val tempBuffer = Buffer()
                private var cacheRequestClosed = false

                override suspend fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long {
                    check(tempBuffer.size == 0L)
                    val bytesRead: Long
                    try {
                        bytesRead = source.read(tempBuffer, byteCount)
                    } catch (e: Throwable) {
                        // Abort the edit on ANY failure incl. cancellation, not just IOException.
                        if (!cacheRequestClosed) {
                            cacheRequestClosed = true
                            cacheRequest.abort() // launches into the cache's own commitScope
                            tempBuffer.clear()
                        }
                        throw e
                    }

                    if (bytesRead == -1L) {
                        if (!cacheRequestClosed) {
                            cacheRequestClosed = true
                            // Fault-hiding: a cache-disk failure must not fail the network stream.
                            try {
                                cacheBody.close() // The cache response is complete!
                            } catch (_: IOException) {
                                cacheRequest.abort()
                            }
                        }
                        return -1
                    }
                    tempBuffer.copyTo(sink)
                    // Fault-hiding (cf. OkHttp): a cache-write failure stops caching but must not
                    // fail the network stream.
                    if (!cacheRequestClosed) {
                        try {
                            cacheBody.write(tempBuffer, bytesRead)
                        } catch (_: IOException) {
                            cacheRequestClosed = true
                            cacheRequest.abort()
                        }
                    }
                    tempBuffer.clear()
                    return bytesRead
                }

                @Throws(IOException::class)
                override fun close() {
                    if (!cacheRequestClosed) {
                        cacheRequestClosed = true
                        cacheRequest.abort()
                    }
                    source.close()
                }
            }

        val contentType = response.header("Content-Type")?.toMediaTypeOrNull()
        val contentLength = response.body.contentLength()
        return response
            .newBuilder()
            .body(object : ResponseBody() {
                override fun contentType(): MediaType? = contentType
                override fun contentLength(): Long = contentLength
                override fun asyncSource(): AsyncSource = cacheWritingSource
            })
            .build()
    }

    companion object {
        /** Combines cached headers with a network headers as defined by RFC 7234, 4.3.4. */
        private fun combine(
            cachedHeaders: Headers,
            networkHeaders: Headers,
        ): Headers {
            val result = HeadersBuilder()

            for (index in 0 until cachedHeaders.size) {
                val fieldName = cachedHeaders.name(index)
                val value = cachedHeaders.value(index)
                if ("Warning".equals(fieldName, ignoreCase = true) && value.startsWith("1")) {
                    // Drop 100-level freshness warnings.
                    continue
                }
                if (isContentSpecificHeader(fieldName) ||
                    !isEndToEnd(fieldName) ||
                    networkHeaders[fieldName] == null
                ) {
                    result.addLenient(fieldName, value)
                }
            }

            for (index in 0 until networkHeaders.size) {
                val fieldName = networkHeaders.name(index)
                if (!isContentSpecificHeader(fieldName) && isEndToEnd(fieldName)) {
                    result.addLenient(fieldName, networkHeaders.value(index))
                }
            }

            return result.build()
        }

        /**
         * Returns true if [fieldName] is an end-to-end HTTP header, as defined by RFC 2616,
         * 13.5.1.
         */
        private fun isEndToEnd(fieldName: String): Boolean =
            !"Connection".equals(fieldName, ignoreCase = true) &&
                !"Keep-Alive".equals(fieldName, ignoreCase = true) &&
                !"Proxy-Authenticate".equals(fieldName, ignoreCase = true) &&
                !"Proxy-Authorization".equals(fieldName, ignoreCase = true) &&
                !"TE".equals(fieldName, ignoreCase = true) &&
                !"Trailers".equals(fieldName, ignoreCase = true) &&
                !"Transfer-Encoding".equals(fieldName, ignoreCase = true) &&
                !"Upgrade".equals(fieldName, ignoreCase = true)

        /**
         * Returns true if [fieldName] is content specific and therefore should always be used
         * from cached headers.
         */
        private fun isContentSpecificHeader(fieldName: String): Boolean =
            "Content-Length".equals(fieldName, ignoreCase = true) ||
                "Content-Encoding".equals(fieldName, ignoreCase = true) ||
                "Content-Type".equals(fieldName, ignoreCase = true)
    }
}
