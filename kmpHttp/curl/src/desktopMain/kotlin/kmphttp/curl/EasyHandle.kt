package kmphttp.curl

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap.alloc
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import libcurl.CURLE_OK
import libcurl.CURLINFO_CAINFO
import libcurl.CURLINFO_HTTP_VERSION
import libcurl.CURLINFO_PRIVATE
import libcurl.CURLINFO_RESPONSE_CODE
import libcurl.CURLM_OK
import libcurl.CURLMcode
import libcurl.CURLOPT_ACCEPT_ENCODING
import libcurl.CURLOPT_CAINFO_BLOB
import libcurl.CURLOPT_CUSTOMREQUEST
import libcurl.CURLOPT_FOLLOWLOCATION
import libcurl.CURLOPT_HEADERDATA
import libcurl.CURLOPT_HEADERFUNCTION
import libcurl.CURLOPT_HTTPHEADER
import libcurl.CURLOPT_NOBODY
import libcurl.CURLOPT_PINNEDPUBLICKEY
import libcurl.CURLOPT_POSTFIELDSIZE
import libcurl.CURLOPT_PRIVATE
import libcurl.CURLOPT_READDATA
import libcurl.CURLOPT_READFUNCTION
import libcurl.CURLOPT_SSLCERTTYPE
import libcurl.CURLOPT_SSLCERT_BLOB
import libcurl.CURLOPT_SSLKEYTYPE
import libcurl.CURLOPT_SSLKEY_BLOB
import libcurl.CURLOPT_SSL_CTX_DATA
import libcurl.CURLOPT_SSL_CTX_FUNCTION
import libcurl.CURLOPT_UPLOAD
import libcurl.CURLOPT_URL
import libcurl.CURLOPT_VERBOSE
import libcurl.CURLOPT_WRITEDATA
import libcurl.CURLOPT_WRITEFUNCTION
import libcurl.CURLPAUSE_CONT
import libcurl.CURL_BLOB_COPY
import libcurl.CURL_HTTP_VERSION_1_0
import libcurl.CURL_HTTP_VERSION_1_1
import libcurl.CURL_HTTP_VERSION_2_0
import libcurl.CURL_HTTP_VERSION_3
import libcurl.curl_blob
import libcurl.curl_easy_cleanup
import libcurl.curl_easy_getinfo
import libcurl.curl_easy_init
import libcurl.curl_easy_pause
import libcurl.curl_easy_setopt
import libcurl.curl_multi_add_handle
import libcurl.curl_multi_remove_handle
import libcurl.curl_slist
import libcurl.curl_slist_append
import libcurl.curl_slist_free_all
import okio.IOException
import openssl.BIO_free
import openssl.BIO_new_mem_buf
import openssl.PEM_read_bio_X509
import openssl.SSL_CTX
import openssl.SSL_CTX_get_cert_store
import openssl.X509_STORE_add_cert
import openssl.X509_free
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** HTTP version negotiated for a response (from CURLINFO_HTTP_VERSION). */
enum class CurlHttpVersion { HTTP_1_0, HTTP_1_1, HTTP_2, HTTP_3, UNKNOWN }

/**
 * Type alias for curl easy handle.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
class EasyHandle private constructor(
    private val pointer: COpaquePointer
) {
    private val lock = SynchronizedObject()
    private var cleanedUp = AtomicBoolean(false)
    private var multiInterface: COpaquePointer? = null
    private val thisRef = StableRef.create(this)

    private var headersPointer: CPointer<curl_slist>? = null
    private var extraCaCertsRef: StableRef<List<ByteArray>>? = null

    init {
        curl_easy_setopt(pointer, CURLOPT_PRIVATE, thisRef.asCPointer())
    }

    fun setVerbose(verbose: Boolean) {
        curl_easy_setopt(pointer, CURLOPT_VERBOSE, if (verbose) 1L else 0L)
    }

    fun setUrl(url: String) {
        curl_easy_setopt(pointer, CURLOPT_URL, url)
    }

    fun setFollowLocation(follow: Boolean) {
        curl_easy_setopt(pointer, CURLOPT_FOLLOWLOCATION, if (follow) 1L else 0L)
    }

    fun setMethod(method: String) {
        when {
            "GET".contentEquals(method, ignoreCase = true) -> Unit // default
            "HEAD".contentEquals(method, ignoreCase = true) -> curl_easy_setopt(
                pointer,
                CURLOPT_NOBODY,
                1L
            )

            else -> curl_easy_setopt(pointer, CURLOPT_CUSTOMREQUEST, method)
        }
    }

    fun setHeaders(headers: List<Pair<String, String>>) {
        this.headersPointer?.let {
            curl_slist_free_all(it)
            this.headersPointer = null
        }

        for ((name, value) in headers) {
            this.headersPointer = curl_slist_append(this.headersPointer, "$name: $value")
        }
        curl_easy_setopt(pointer, CURLOPT_HTTPHEADER, this.headersPointer)
    }

    fun setAcceptEncoding(accept: String?) {
        curl_easy_setopt(pointer, CURLOPT_ACCEPT_ENCODING, accept)
    }

    /** Replaces the trust store with the given PEM CA bundle (CURLOPT_CAINFO_BLOB). */
    fun setCaInfoBlob(pemBundle: ByteArray) {
        setBlobOption(CURLOPT_CAINFO_BLOB, pemBundle)
    }

    /**
     * Adds [pemCerts] (each one or more PEM `CERTIFICATE` blocks) to the connection's default trust
     * store *in addition to* whatever curl already trusts (the system defaults), via
     * CURLOPT_SSL_CTX_FUNCTION + OpenSSL `X509_STORE_add_cert`. Unlike [setCaInfoBlob], which
     * *replaces* the store, this augments it — the way to trust an extra CA without losing the
     * system roots. Requires curl's OpenSSL backend.
     */
    fun addCaCertsToDefaultStore(pemCerts: List<ByteArray>) {
        if (pemCerts.isEmpty()) return
        extraCaCertsRef?.dispose()
        val ref = StableRef.create(pemCerts)
        extraCaCertsRef = ref
        curl_easy_setopt(pointer, CURLOPT_SSL_CTX_FUNCTION, sslCtxCallback)
        curl_easy_setopt(pointer, CURLOPT_SSL_CTX_DATA, ref.asCPointer())
    }

    /** Configures a PEM client certificate chain + (unencrypted) PEM private key. */
    fun setClientCertPem(certPem: ByteArray, keyPem: ByteArray) {
        setBlobOption(CURLOPT_SSLCERT_BLOB, certPem)
        curl_easy_setopt(pointer, CURLOPT_SSLCERTTYPE, "PEM")
        setBlobOption(CURLOPT_SSLKEY_BLOB, keyPem)
        curl_easy_setopt(pointer, CURLOPT_SSLKEYTYPE, "PEM")
    }

    private fun setBlobOption(option: libcurl.CURLoption, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        bytes.usePinned { pinned ->
            memScoped {
                val blob = alloc<curl_blob>()
                blob.data = pinned.addressOf(0)
                blob.len = bytes.size.convert()
                blob.flags = CURL_BLOB_COPY.convert()
                curl_easy_setopt(pointer, option, blob.ptr)
            }
        }
    }

    /** Returns the compiled-in default CA bundle path (CURLINFO_CAINFO), or null. */
    fun getDefaultCaInfo(): String? {
        memScoped {
            val holder = alloc<COpaquePointerVar>()
            curl_easy_getinfo(pointer, CURLINFO_CAINFO, holder.ptr)
            return holder.value?.reinterpret<ByteVar>()?.toKString()
        }
    }

    /** Sets the expected pinned public keys (CURLOPT_PINNEDPUBLICKEY), e.g. "sha256//<b64>;...". */
    fun setPinnedPublicKey(value: String) {
        curl_easy_setopt(pointer, CURLOPT_PINNEDPUBLICKEY, value)
    }

    fun setContentLength(length: Long) {
        curl_easy_setopt(pointer, CURLOPT_POSTFIELDSIZE, length)
    }

    fun setBody(readFunction: COpaquePointer, readData: COpaquePointer) {
        curl_easy_setopt(pointer, CURLOPT_UPLOAD, 1L)
        curl_easy_setopt(pointer, CURLOPT_READFUNCTION, readFunction)
        curl_easy_setopt(pointer, CURLOPT_READDATA, readData)
    }

    fun setHeaderFunction(headerFunction: COpaquePointer, headerData: COpaquePointer) {
        curl_easy_setopt(pointer, CURLOPT_HEADERFUNCTION, headerFunction)
        curl_easy_setopt(pointer, CURLOPT_HEADERDATA, headerData)
    }

    fun setWriteFunction(writeFunction: COpaquePointer, writeData: COpaquePointer) {
        curl_easy_setopt(pointer, CURLOPT_WRITEFUNCTION, writeFunction)
        curl_easy_setopt(pointer, CURLOPT_WRITEDATA, writeData)
    }

    fun getResponseCode(): Long {
        check(!cleanedUp.load())
        memScoped {
            val responseCodeHolder = alloc<LongVar>()
            curl_easy_getinfo(pointer, CURLINFO_RESPONSE_CODE, responseCodeHolder.ptr)
            return responseCodeHolder.value
        }
    }

    fun getHttpVersion(): CurlHttpVersion {
        check(!cleanedUp.load())
        memScoped {
            val version = alloc<LongVar>()
            curl_easy_getinfo(pointer, CURLINFO_HTTP_VERSION, version.ptr)

            // The CURL_HTTP_VERSION_* constants are typed Long on Apple but UInt on Linux; normalize
            // both sides to Long so this compiles on every desktop target.
            return when (version.value) {
                CURL_HTTP_VERSION_1_0.toLong() -> CurlHttpVersion.HTTP_1_0
                CURL_HTTP_VERSION_1_1.toLong() -> CurlHttpVersion.HTTP_1_1
                CURL_HTTP_VERSION_2_0.toLong() -> CurlHttpVersion.HTTP_2
                CURL_HTTP_VERSION_3.toLong() -> CurlHttpVersion.HTTP_3
                else -> CurlHttpVersion.UNKNOWN
            }
        }
    }

    /**
     * Add to a multi interface.
     * [addTo] [unpause] [cleanUp] should be called in multi interface event loop.
     */
    fun addTo(multiInterface: COpaquePointer): CURLMcode {
        synchronized(lock) {
            check(!cleanedUp.load()) { "Already cleaned up" }
            check(this.multiInterface == null) { "Already added" }
            val res = curl_multi_add_handle(multiInterface, pointer)
            if (res == CURLM_OK) {
                this.multiInterface = multiInterface
            }
            return res
        }
    }

    /**
     * [addTo] [unpause] [cleanUp] should be called in multi interface event loop.
     */
    fun unpause() {
        if (!cleanedUp.load()) {
            curl_easy_pause(pointer, CURLPAUSE_CONT)
        }
    }

    /**
     * [addTo] [unpause] [cleanUp] should be called in multi interface event loop.
     */
    fun cleanUp(): Boolean {
        if (cleanedUp.compareAndSet(false, true)) {
            synchronized(lock) {
                this.multiInterface?.let {
                    curl_multi_remove_handle(it, pointer)
                    this.multiInterface = null
                }
                curl_easy_cleanup(pointer)
                // curl does not own the header slist — free it here (only setHeaders freed a
                // PREVIOUS list, so without this every executed request leaked its full header
                // list in native memory). Safe after curl_easy_cleanup: the transfer is done.
                headersPointer?.let {
                    curl_slist_free_all(it)
                    headersPointer = null
                }
                extraCaCertsRef?.dispose()
                extraCaCertsRef = null
                thisRef.dispose()
            }
            return true
        }
        return false
    }

    fun isCleanedUp() = cleanedUp.load()

    override fun toString(): String {
        return "EasyHandle(pointer=$pointer)"
    }

    companion object {
        /**
         * CURLOPT_SSL_CTX_FUNCTION callback: curl hands us the freshly-built OpenSSL `SSL_CTX` and
         * our [CURLOPT_SSL_CTX_DATA] (a StableRef to the extra-CA PEM list). We parse each PEM and
         * add it to the context's existing `X509_STORE`, so the custom CAs are trusted alongside the
         * defaults curl already loaded. Returns CURLE_OK; on any failure we leave the store as-is and
         * let normal verification proceed/fail.
         */
        private val sslCtxCallback = staticCFunction {
                _: COpaquePointer?, sslCtx: COpaquePointer?, userdata: COpaquePointer? ->
            if (sslCtx == null || userdata == null) return@staticCFunction CURLE_OK
            val pemCerts = userdata.asStableRef<List<ByteArray>>().get()
            val store = SSL_CTX_get_cert_store(sslCtx.reinterpret<SSL_CTX>())
                ?: return@staticCFunction CURLE_OK
            for (pem in pemCerts) {
                if (pem.isEmpty()) continue
                pem.usePinned { pinned ->
                    val bio = BIO_new_mem_buf(pinned.addressOf(0), pem.size) ?: return@usePinned
                    try {
                        // A single PEM blob may hold a chain — add every CERTIFICATE block.
                        while (true) {
                            val cert = PEM_read_bio_X509(bio, null, null, null) ?: break
                            X509_STORE_add_cert(store, cert)
                            X509_free(cert)
                        }
                    } finally {
                        BIO_free(bio)
                    }
                }
            }
            CURLE_OK
        }

        fun create(): EasyHandle {
            val pointer = curl_easy_init() ?: throw IOException("curl easy init failed")
            return EasyHandle(pointer)
        }

        fun from(pointer: COpaquePointer): EasyHandle {
            val easyHandle = memScoped {
                val privatePtr = alloc<COpaquePointerVar>()
                curl_easy_getinfo(pointer, CURLINFO_PRIVATE, privatePtr.ptr)
                privatePtr.value?.asStableRef<EasyHandle>()?.get()
            }
            if (easyHandle == null) {
                throw IllegalArgumentException("Invalid easy handle pointer")
            }
            return easyHandle
        }
    }
}
