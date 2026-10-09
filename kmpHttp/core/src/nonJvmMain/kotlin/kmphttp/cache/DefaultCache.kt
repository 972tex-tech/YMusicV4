package kmphttp.cache

import kmpcommon.io.AsyncSink
import kmpcommon.io.AsyncSource
import kmpcommon.io.asAsyncSink
import kmpcommon.io.asAsyncSource
import kmpcommon.log.Logger
import kmphttp.Headers
import kmphttp.HeadersBuilder
import kmphttp.HttpCache
import kmphttp.HttpCache.CacheRequest
import kmphttp.HttpUrl
import kmphttp.MediaType
import kmphttp.MediaTypeX.toMediaTypeOrNull
import kmphttp.Protocol
import kmphttp.Request
import kmphttp.RequestBuilder
import kmphttp.Response
import kmphttp.ResponseBody
import kmphttp.ResponseBuilder
import kmphttp.internal.ForwardingAsyncSink
import kmphttp.internal.ForwardingAsyncSource
import kmphttp.internal.HttpMethod
import kmphttp.internal.StatusLine
import kmphttp.internal.closeQuietly
import kmphttp.toHttpUrlOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Sink
import okio.Source
import okio.buffer
import okio.use
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class DefaultCache(
    directory: Path,
    maxSize: Long,
    fileSystem: FileSystem = FileSystem.SYSTEM
) : HttpCache {

    /** Diagnostics sink, attached by the owning client at build time (journal faults surface here). */
    var logger: kmpcommon.log.Logger?
        get() = cache.logger
        set(value) {
            cache.logger = value
        }

    internal val cache =
        DiskLruCache(
            fileSystem = fileSystem,
            directory = directory,
            appVersion = VERSION,
            valueCount = ENTRY_COUNT,
            maxSize = maxSize,
        )

    private val isClosed = AtomicBoolean(false)
    private val commitScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    override val initialized: Boolean
        get() = cache.initialized

    override val closed: Boolean
        get() = isClosed.load()

    override val currentSize: Long
        get() = cache.currentSize

    /**
     * Initialize the cache. This will include reading the journal files from the storage and building
     * up the necessary in-memory cache information.
     *
     * The initialization time may vary depending on the journal file size and the current actual
     * cache size. The application needs to be aware of calling this function during the
     * initialization phase and preferably in a background worker thread.
     *
     * Note that if the application chooses to not call this method to initialize the cache. By
     * default, KmpHttp will perform lazy initialization upon the first usage of the cache.
     */
    override suspend fun initialize() {
        cache.initialize()
    }

    override suspend fun flush() {
        cache.flush()
    }

    override suspend fun close() {
        if (isClosed.compareAndSet(false, true)) {
            cache.close()
        }
    }

    override suspend fun get(request: Request): Response? {
        if (isClosed.load()) return null
        val key = key(request.url)
        val snapshot: DiskLruCache.Snapshot =
            try {
                cache.get(key) ?: return null
            } catch (e: IOException) {
                logger?.log(Logger.Level.WARN, "cache read failed — treated as a miss", e)
                return null
            }

        val entry: Entry =
            try {
                Entry.create(snapshot.getSource(ENTRY_METADATA))
            } catch (e: Throwable) {
                snapshot.closeQuietly()
                if (e is IOException) {
                    logger?.log(Logger.Level.WARN, "cache entry metadata unreadable — treated as a miss", e)
                    return null
                }
                throw e
            }

        val response = entry.response(snapshot)
        if (!entry.matches(request, response)) {
            response.body.closeQuietly()
            return null
        }

        return response
    }

    override suspend fun put(response: Response): CacheRequest? {
        if (isClosed.load()) return null
        val requestMethod = response.request.method

        if (HttpMethod.invalidatesCache(response.request.method)) {
            try {
                remove(response.request)
            } catch (e: IOException) {
                logger?.log(Logger.Level.WARN, "cache invalidation failed", e)
            }
            return null
        }

        if (requestMethod != "GET") {
            // Don't cache non-GET responses. We're technically allowed to cache HEAD, QUERY and some
            // POST requests, but the complexity of doing so is high and the benefit is low.
            return null
        }

        if (response.hasVaryAll()) {
            return null
        }

        val entry = Entry(response)
        var editor: DiskLruCache.Editor? = null
        try {
            editor = cache.edit(key(response.request.url)) ?: return null
            entry.writeTo(editor)
            return RealCacheRequest.create(editor, commitScope, logger)
        } catch (e: Throwable) {
            // NonCancellable so the abort still runs when the caller is cancelled (abortQuietly
            // suspends). IOException -> null (uncacheable); anything else rethrows.
            withContext(NonCancellable) { abortQuietly(editor, e) }
            if (e is IOException) {
                logger?.log(Logger.Level.WARN, "cache put failed — response not cached", e)
                return null
            }
            throw e
        }
    }

    override suspend fun remove(request: Request) {
        cache.remove(key(request.url))
    }

    override suspend fun update(
        cached: Response,
        network: Response,
    ) {
        if (isClosed.load()) return

        val entry = Entry(network)
        val snapshot = (cached.body as CacheResponseBody).snapshot
        var editor: DiskLruCache.Editor? = null
        try {
            editor = snapshot.edit() ?: return // edit() returns null if snapshot is not current.
            entry.writeTo(editor)
            editor.commit()
        } catch (e: IOException) {
            abortQuietly(editor, e)
            logger?.log(Logger.Level.WARN, "cache update failed", e)
        }
    }

    private suspend fun abortQuietly(editor: DiskLruCache.Editor?, primary: Throwable) {
        editor ?: return
        try {
            editor.abort()
        } catch (abortError: IOException) {
            primary.addSuppressed(abortError)
        }
    }

    /**
     * Get current cached size in bytes, return 0 if the cache not yet initialized.
     */
    fun size(): Long = cache.currentSize

    /** Max size of the cache (in bytes). */
    fun maxSize(): Long = cache.maxSize

    @OptIn(ExperimentalAtomicApi::class)
    private class RealCacheRequest private constructor(
        private val editor: DiskLruCache.Editor,
        private val cacheOut: Sink,
        private val commitScope: CoroutineScope,
        private val logger: Logger?,
    ) : CacheRequest {
        companion object {
            fun create(
                editor: DiskLruCache.Editor,
                commitScope: CoroutineScope,
                logger: Logger?,
            ): RealCacheRequest {
                val cacheOut = editor.newSink(ENTRY_BODY)
                return RealCacheRequest(editor, cacheOut, commitScope, logger)
            }
        }

        private val done = AtomicBoolean(false)
        private val body: AsyncSink = object : ForwardingAsyncSink(cacheOut.asAsyncSink()) {
            override fun close() {
                super.close()
                if (done.compareAndSet(false, true)) {
                    commitScope.launch {
                        try {
                            editor.commit()
                        } catch (e: IOException) {
                            logger?.log(Logger.Level.WARN, "cache commit failed — response not cached", e)
                        }
                    }
                }
            }
        }

        override fun abort() {
            if (done.compareAndSet(false, true)) {
                cacheOut.closeQuietly()
                commitScope.launch {
                    try {
                        editor.abort()
                    } catch (e: IOException) {
                        logger?.log(Logger.Level.WARN, "cache abort failed", e)
                    }
                }
            }
        }

        override fun bodyWriter() = body
    }

    private class Entry(
        private val url: HttpUrl,
        private val varyHeaders: Headers,
        private val requestMethod: String,
        private val protocol: Protocol,
        private val code: Int,
        private val message: String,
        private val responseHeaders: Headers,
        private val sentRequestMillis: Long,
        private val receivedResponseMillis: Long
    ) {
        companion object {
            /** Synthetic response header: the local time when the request was sent. */
            private const val SENT_MILLIS = "KmpHttp-Sent-Millis"

            /** Synthetic response header: the local time when the response was received. */
            private const val RECEIVED_MILLIS = "KmpHttp-Received-Millis"

            /**
             * Reads an entry from an input stream. A typical entry looks like this:
             *
             * ```
             * http://google.com/foo
             * GET
             * 2
             * Accept-Language: fr-CA
             * Accept-Charset: UTF-8
             * HTTP/1.1 200 OK
             * 3
             * Content-Type: image/png
             * Content-Length: 100
             * Cache-Control: max-age=600
             * ```
             *
             * The file is newline separated. The first two lines are the URL and the request method. Next
             * is the number of HTTP Vary request header lines, followed by those lines.
             *
             * Next is the response status line, followed by the number of HTTP response header lines,
             * followed by those lines.
             */
            fun create(source: Source): Entry {
                val source = source.buffer()
                try {
                    val urlLine = source.readUtf8LineStrict()
                    // Choice here is between failing with a correct RuntimeException
                    // or mostly silently with an IOException
                    val url = urlLine.toHttpUrlOrNull()
                        ?: throw IOException("Cache corruption for $urlLine")
                    val requestMethod = source.readUtf8LineStrict()
                    val varyHeadersBuilder = HeadersBuilder()
                    val varyRequestHeaderLineCount = readInt(source)
                    for (i in 0 until varyRequestHeaderLineCount) {
                        varyHeadersBuilder.addLenient(source.readUtf8LineStrict())
                    }
                    val varyHeaders = varyHeadersBuilder.build()

                    val statusLine = StatusLine.parse(source.readUtf8LineStrict())
                    val protocol = statusLine.protocol
                    val code = statusLine.code
                    val message = statusLine.message
                    val responseHeadersBuilder = HeadersBuilder()
                    val responseHeaderLineCount = readInt(source)
                    for (i in 0 until responseHeaderLineCount) {
                        responseHeadersBuilder.addLenient(source.readUtf8LineStrict())
                    }
                    val sendRequestMillisString = responseHeadersBuilder[SENT_MILLIS]
                    val receivedResponseMillisString = responseHeadersBuilder[RECEIVED_MILLIS]
                    if (sendRequestMillisString == null || receivedResponseMillisString == null) {
                        throw IOException("Cache headers corrupted")
                    }
                    responseHeadersBuilder.removeAll(SENT_MILLIS)
                    responseHeadersBuilder.removeAll(RECEIVED_MILLIS)
                    val responseHeaders = responseHeadersBuilder.build()
                    return Entry(
                        url = url,
                        varyHeaders = varyHeaders,
                        requestMethod = requestMethod,
                        protocol = protocol,
                        code = code,
                        message = message,
                        responseHeaders = responseHeaders,
                        sentRequestMillis = sendRequestMillisString.toLong(),
                        receivedResponseMillis = receivedResponseMillisString.toLong()
                    )
                } finally {
                    source.close()
                }
            }
        }

        constructor(response: Response) : this(
            response.request.url,
            response.varyHeaders(),
            response.request.method,
            response.protocol,
            response.code,
            response.message,
            response.headers,
            response.sentRequestAtMillis,
            response.receivedResponseAtMillis
        )

        fun writeTo(editor: DiskLruCache.Editor) {
            val metadata = Buffer()
            metadata.writeUtf8(url.toString()).writeByte('\n'.code)
            metadata.writeUtf8(requestMethod).writeByte('\n'.code)
            metadata.writeDecimalLong(varyHeaders.size.toLong()).writeByte('\n'.code)
            for (i in 0 until varyHeaders.size) {
                metadata
                    .writeUtf8(varyHeaders.name(i))
                    .writeUtf8(": ")
                    .writeUtf8(varyHeaders.value(i))
                    .writeByte('\n'.code)
            }

            metadata.writeUtf8(StatusLine(protocol, code, message).toString()).writeByte('\n'.code)
            metadata.writeDecimalLong((responseHeaders.size + 2).toLong()).writeByte('\n'.code)
            for (i in 0 until responseHeaders.size) {
                metadata
                    .writeUtf8(responseHeaders.name(i))
                    .writeUtf8(": ")
                    .writeUtf8(responseHeaders.value(i))
                    .writeByte('\n'.code)
            }
            metadata
                .writeUtf8(SENT_MILLIS)
                .writeUtf8(": ")
                .writeDecimalLong(sentRequestMillis)
                .writeByte('\n'.code)
            metadata
                .writeUtf8(RECEIVED_MILLIS)
                .writeUtf8(": ")
                .writeDecimalLong(receivedResponseMillis)
                .writeByte('\n'.code)

            editor.newSink(ENTRY_METADATA).use { sink ->
                sink.write(metadata, metadata.size)
            }
        }

        fun matches(
            request: Request,
            response: Response,
        ): Boolean =
            url == request.url &&
                requestMethod == request.method &&
                varyMatches(response, varyHeaders, request)

        fun response(snapshot: DiskLruCache.Snapshot): Response {
            val contentType = responseHeaders["Content-Type"]
            val contentLength = responseHeaders["Content-Length"]
            val cacheRequest = RequestBuilder()
                .url(url)
                .method(requestMethod, null)
                .headers(varyHeaders)
                .build()
            return ResponseBuilder()
                .request(cacheRequest)
                .protocol(protocol)
                .code(code)
                .message(message)
                .headers(responseHeaders)
                .body(
                    CacheResponseBody(
                        snapshot,
                        contentType?.toMediaTypeOrNull(),
                        contentLength?.toLongOrNull() ?: -1
                    )
                )
                .sentRequestAtMillis(sentRequestMillis)
                .receivedResponseAtMillis(receivedResponseMillis)
                .build()
        }
    }

    private class CacheResponseBody(
        val snapshot: DiskLruCache.Snapshot,
        val contentType: MediaType?,
        val contentLength: Long
    ) : ResponseBody() {
        private val bodySource: AsyncSource =
            object : ForwardingAsyncSource(snapshot.getSource(ENTRY_BODY).asAsyncSource()) {
                override fun close() {
                    snapshot.close()
                    super.close()
                }
            }

        override fun asyncSource(): AsyncSource = bodySource
        override fun close() {
            bodySource.close()
        }

        override fun contentLength(): Long = contentLength
        override fun contentType(): MediaType? = contentType
    }

    companion object {
        private const val VERSION = 202512
        private const val ENTRY_METADATA = 0
        private const val ENTRY_BODY = 1
        private const val ENTRY_COUNT = 2

        fun key(url: HttpUrl): String =
            url
                .toString()
                .encodeUtf8()
                .md5()
                .hex()

        internal fun readInt(source: BufferedSource): Int {
            try {
                val result = source.readDecimalLong()
                val line = source.readUtf8LineStrict()
                if (result < 0L || result > Int.MAX_VALUE || line.isNotEmpty()) {
                    throw IOException("expected an int but was \"$result$line\"")
                }
                return result.toInt()
            } catch (e: NumberFormatException) {
                throw IOException(e.message, e)
            }
        }

        /**
         * Returns true if none of the Vary headers have changed between [cachedRequest] and
         * [newRequest].
         */
        fun varyMatches(
            cachedResponse: Response,
            cachedRequest: Headers,
            newRequest: Request,
        ): Boolean =
            cachedResponse.headers.varyFields().none { field ->
                cachedRequest.values(field) != newRequest.headers(field)
            }

        /** Returns true if a Vary header contains an asterisk. Such responses cannot be cached. */
        fun Response.hasVaryAll(): Boolean = "*" in headers.varyFields()

        /**
         * Returns the names of the request headers that need to be checked for equality when caching.
         */
        private fun Headers.varyFields(): List<String> {
            var result: MutableList<String>? = null
            for (i in 0 until size) {
                if (!"Vary".equals(name(i), ignoreCase = true)) {
                    continue
                }

                val value = value(i)
                if (result == null) {
                    result = mutableListOf()
                }
                for (varyField in value.split(',')) {
                    result.add(varyField.trim())
                }
            }
            return result ?: emptyList()
        }

        /**
         * Returns the subset of the headers in this's request that impact the content of this's body.
         */
        fun Response.varyHeaders(): Headers {
            // Use the request headers sent over the network, since that's what the response varies on.
            // Otherwise OkHttp-supplied headers like "Accept-Encoding: gzip" may be lost.
            val requestHeaders = networkResponse!!.request.headers
            val responseHeaders = headers
            return varyHeaders(requestHeaders, responseHeaders)
        }

        /**
         * Returns the subset of the headers in [requestHeaders] that impact the content of the
         * response's body.
         */
        private fun varyHeaders(
            requestHeaders: Headers,
            responseHeaders: Headers,
        ): Headers {
            val varyFields = responseHeaders.varyFields()
            if (varyFields.isEmpty()) return Headers.EMPTY

            val result = HeadersBuilder()
            for (i in 0 until requestHeaders.size) {
                val fieldName = requestHeaders.name(i)
                if (varyFields.any { fieldName.equals(it, ignoreCase = true) }) {
                    result.add(fieldName, requestHeaders.value(i))
                }
            }
            return result.build()
        }
    }
}
