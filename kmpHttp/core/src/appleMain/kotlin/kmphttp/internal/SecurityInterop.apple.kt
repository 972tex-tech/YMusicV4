@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, UnsafeNumber::class)

package kmphttp.internal

import kmphttp.SslConfig
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import okio.ByteString
import okio.ByteString.Companion.toByteString
import platform.CoreFoundation.CFArrayCreate
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFArrayRefVar
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFNumberGetValue
import platform.CoreFoundation.CFNumberRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringGetCString
import platform.CoreFoundation.CFStringGetCStringPtr
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.CFStringGetMaximumSizeForEncoding
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFNumberSInt32Type
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeArrayCallBacks
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLCredentialPersistence
import platform.Foundation.credentialWithIdentity
import platform.Security.SecCertificateCopyData
import platform.Security.SecCertificateCopyKey
import platform.Security.SecCertificateCreateWithData
import platform.Security.SecCertificateRef
import platform.Security.SecIdentityRef
import platform.Security.SecKeyCopyAttributes
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyRef
import platform.Security.SecPKCS12Import
import platform.Security.SecTrustCopyCertificateChain
import platform.Security.SecTrustRef
import platform.Security.SecTrustSetAnchorCertificates
import platform.Security.SecTrustSetAnchorCertificatesOnly
import platform.Security.errSecSuccess
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecAttrKeyTypeRSA
import platform.Security.kSecImportExportPassphrase
import platform.Security.kSecImportItemIdentity
import platform.darwin.OSStatus
import platform.posix.memcpy

// Low-level Security / CoreFoundation interop backing SslConfig.apple.kt. Kept here so the policy
// file stays focused on what SslConfig does, not how the C APIs are driven.

/**
 * Installs [trustedCAs] (DER) as anchors on [trust]. [anchorsOnly] = true trusts only these;
 * false adds them to the system anchors. Returns false if any cert fails to parse.
 */
internal fun applyCustomAnchors(
    trust: SecTrustRef,
    trustedCAs: List<ByteString>,
    anchorsOnly: Boolean
): Boolean {
    val anchors = trustedCAs.mapNotNull { it.toByteArray().toSecCertificate() }
    if (anchors.size != trustedCAs.size) {
        anchors.forEach { it.release() }
        return false
    }
    try {
        memScoped {
            val values = allocArray<COpaquePointerVar>(anchors.size)
            anchors.forEachIndexed { i, c -> values[i] = c }
            // kCFTypeArrayCallBacks is load-bearing: the array must retain the certs, because the
            // finally below releases OUR retain and trust evaluation dereferences them later
            // through this array. With null callbacks the SecTrust would evaluate against freed
            // SecCertificateRefs (use-after-free).
            val cfArray = CFArrayCreate(
                kCFAllocatorDefault, values, anchors.size.convert(), kCFTypeArrayCallBacks.ptr,
            )
            try {
                SecTrustSetAnchorCertificates(trust, cfArray)
                SecTrustSetAnchorCertificatesOnly(trust, anchorsOnly)
            } finally {
                cfArray?.release()
            }
        }
    } finally {
        anchors.forEach { it.release() }
    }
    return true
}

/**
 * True if the presented chain contains a cert that exactly (DER) equals one of [trustedCAs] AND is
 * valid for [host] (matched against the cert's SAN dNSNames, falling back to its CN).
 */
internal fun chainContainsTrustedCertForHost(
    trust: SecTrustRef,
    trustedCAs: List<ByteString>,
    host: String
): Boolean {
    val chain = SecTrustCopyCertificateChain(trust) ?: return false
    try {
        val count = CFArrayGetCount(chain)
        for (i in 0 until count.toInt()) {
            val cert: SecCertificateRef =
                CFArrayGetValueAtIndex(chain, i.convert())?.reinterpret() ?: continue
            val data = SecCertificateCopyData(cert) ?: continue
            try {
                val der = data.toByteArray()
                if (trustedCAs.any { it == der.toByteString() } &&
                    hostMatchesCert(host, certHostNames(der))
                ) {
                    return true
                }
            } finally {
                data.release()
            }
        }
    } finally {
        chain.release()
    }
    return false
}

/** True if any cert in the presented chain matches one of [expected] (`sha256/...`) SPKI pins. */
internal fun chainMatchesPins(trust: SecTrustRef, expected: Set<String>): Boolean {
    val chain = SecTrustCopyCertificateChain(trust) ?: return false
    try {
        val count = CFArrayGetCount(chain)
        for (i in 0 until count.toInt()) {
            val cert: SecCertificateRef =
                CFArrayGetValueAtIndex(chain, i.convert())?.reinterpret() ?: continue
            val pin = cert.spkiSha256Base64() ?: continue
            if ("sha256/$pin" in expected) return true
        }
    } finally {
        chain.release()
    }
    return false
}

/** Imports the PKCS#12 client cert and builds a session credential, or null on failure. */
internal fun clientCertificateCredential(clientCert: SslConfig.ClientCert.Pkcs12): NSURLCredential? {
    val data = clientCert.data.toByteArray().toCFData() ?: return null
    try {
        memScoped {
            val passphrase = CFStringCreateWithCString(
                kCFAllocatorDefault, clientCert.password, kCFStringEncodingUTF8
            ) ?: return null
            val keys = allocArray<COpaquePointerVar>(1)
            val values = allocArray<COpaquePointerVar>(1)
            keys[0] = kSecImportExportPassphrase
            values[0] = passphrase
            val options = CFDictionaryCreate(kCFAllocatorDefault, keys, values, 1, null, null)
            val items = alloc<CFArrayRefVar>()
            try {
                val status: OSStatus = SecPKCS12Import(data, options, items.ptr)
                if (status != errSecSuccess) return null
                val array = items.value ?: return null
                if (CFArrayGetCount(array) <= 0) return null
                val firstItem: CFDictionaryRef =
                    CFArrayGetValueAtIndex(array, 0.convert())?.reinterpret() ?: return null
                val identity: SecIdentityRef =
                    CFDictionaryGetValue(firstItem, kSecImportItemIdentity)?.reinterpret()
                        ?: return null
                // The identity is owned by `array`; the credential retains it, so build it here
                // before releasing the import results.
                return NSURLCredential.credentialWithIdentity(
                    identity = identity,
                    certificates = null,
                    persistence = NSURLCredentialPersistence.NSURLCredentialPersistenceForSession
                )
            } finally {
                items.value?.release()
                options?.release()
                passphrase.release()
            }
        }
    } finally {
        data.release()
    }
}

// region Hostname (SAN / CN) extraction

/** Matches [host] against a cert's identifiers ([names]) with single-label `*.` wildcard support. */
internal fun hostMatchesCert(host: String, names: List<String>): Boolean =
    names.any { name ->
        val n = name.trim()
        when {
            n.isEmpty() -> false
            n.startsWith("*.") -> {
                val dot = host.indexOf('.')
                dot > 0 && host.substring(dot + 1).equals(n.substring(2), ignoreCase = true)
            }
            else -> host.equals(n, ignoreCase = true)
        }
    }

/**
 * Extracts the host identifiers from a DER X.509 cert: the SubjectAltName dNSNames, or — only if
 * there are none — the subject CommonName. Returns empty (fail-closed) on any parse problem.
 *
 * Apple has no cross-platform API to read the SAN (SecCertificateCopyValues is macOS-only), so this
 * walks the DER directly: Certificate -> TBSCertificate -> extensions ([3]) -> SAN (OID 2.5.29.17).
 */
internal fun certHostNames(der: ByteArray): List<String> = try {
    val tbs = Asn1.Reader(der).readSequence().readSequence()
    if (tbs.peekTag() == 0xA0) tbs.skipElement()  // [0] version (optional)
    tbs.skipElement()                             // serialNumber
    tbs.skipElement()                             // signature algorithm
    tbs.skipElement()                             // issuer
    tbs.skipElement()                             // validity
    val subject = tbs.readSequence()              // subject
    tbs.skipElement()                             // subjectPublicKeyInfo
    var extensions: Asn1.Reader? = null
    while (tbs.hasMore()) {
        if (tbs.peekTag() == 0xA3) {
            extensions = tbs.readConstructed(0xA3)
            break
        }
        tbs.skipElement()
    }
    val san = extensions?.let { extractSanDnsNames(it) }.orEmpty()
    if (san.isNotEmpty()) san else listOfNotNull(extractCommonName(subject))
} catch (e: Exception) {
    emptyList()
}

private fun extractSanDnsNames(extensions: Asn1.Reader): List<String> {
    val extSeq = extensions.readSequence()
    while (extSeq.hasMore()) {
        val ext = extSeq.readSequence()
        val oid = ext.readOid()
        if (ext.peekTag() == 0x01) ext.skipElement()       // critical BOOLEAN (optional)
        val (_, octets) = ext.readRaw()                    // extnValue OCTET STRING
        if (oid == "2.5.29.17") {
            val names = mutableListOf<String>()
            val generalNames = Asn1.Reader(octets).readSequence()
            while (generalNames.hasMore()) {
                val (tag, content) = generalNames.readRaw()
                if (tag == 0x82) names.add(content.decodeToString())  // [2] dNSName (IA5String)
            }
            return names
        }
    }
    return emptyList()
}

private fun extractCommonName(subject: Asn1.Reader): String? {
    while (subject.hasMore()) {
        val rdn = subject.readConstructed(0x31)            // SET OF AttributeTypeAndValue
        while (rdn.hasMore()) {
            val atv = rdn.readSequence()
            val oid = atv.readOid()
            val (_, value) = atv.readRaw()
            if (oid == "2.5.4.3") return value.decodeToString()  // commonName
        }
    }
    return null
}

// endregion

// region SPKI pinning

/** SHA-256 (base64) of the certificate's SubjectPublicKeyInfo — OkHttp's `sha256/...` pin value. */
private fun SecCertificateRef.spkiSha256Base64(): String? {
    val key = SecCertificateCopyKey(this) ?: return null
    try {
        val header = key.spkiAsn1Header() ?: return null
        val keyData = SecKeyCopyExternalRepresentation(key, null) ?: return null
        try {
            val spki = header + keyData.toByteArray()
            return spki.toByteString().sha256().base64()
        } finally {
            keyData.release()
        }
    } finally {
        key.release()
    }
}

/** The fixed ASN.1 SPKI prefix for this key, looked up by (type, size-in-bits). */
private fun SecKeyRef.spkiAsn1Header(): ByteArray? {
    val attrs = SecKeyCopyAttributes(this) ?: return null
    try {
        val keyType = CFDictionaryGetValue(attrs, kSecAttrKeyType)?.cfStringToKString() ?: return null
        val sizeNumber: CFNumberRef =
            CFDictionaryGetValue(attrs, kSecAttrKeySizeInBits)?.reinterpret() ?: return null
        val bits = sizeNumber.toInt() ?: return null

        val isRsa = keyType == kSecAttrKeyTypeRSA?.cfStringToKString()
        val isEc = keyType == kSecAttrKeyTypeECSECPrimeRandom?.cfStringToKString()
        return when {
            isRsa && bits == 2048 -> RSA_2048_SPKI_HEADER
            isRsa && bits == 4096 -> RSA_4096_SPKI_HEADER
            isEc && bits == 256 -> EC_P256_SPKI_HEADER
            isEc && bits == 384 -> EC_P384_SPKI_HEADER
            else -> null
        }
    } finally {
        attrs.release()
    }
}

private fun CFNumberRef.toInt(): Int? = memScoped {
    val out = alloc<IntVar>()
    if (CFNumberGetValue(this@toInt, kCFNumberSInt32Type, out.ptr)) out.value else null
}

/** Computes the OkHttp `sha256/...` pin (without prefix) from a DER certificate; for testing. */
internal fun spkiSha256PinFromDer(der: ByteArray): String? =
    der.toSecCertificate()?.let { cert ->
        try {
            cert.spkiSha256Base64()
        } finally {
            cert.release()
        }
    }

// SubjectPublicKeyInfo ASN.1 prefixes prepended to SecKeyCopyExternalRepresentation output.
private val RSA_2048_SPKI_HEADER = intArrayOf(
    0x30, 0x82, 0x01, 0x22, 0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48, 0x86,
    0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00, 0x03, 0x82, 0x01, 0x0f, 0x00
).toByteArrayOfBytes()

private val RSA_4096_SPKI_HEADER = intArrayOf(
    0x30, 0x82, 0x02, 0x22, 0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48, 0x86,
    0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00, 0x03, 0x82, 0x02, 0x0f, 0x00
).toByteArrayOfBytes()

private val EC_P256_SPKI_HEADER = intArrayOf(
    0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02,
    0x01, 0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03,
    0x42, 0x00
).toByteArrayOfBytes()

private val EC_P384_SPKI_HEADER = intArrayOf(
    0x30, 0x76, 0x30, 0x10, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02,
    0x01, 0x06, 0x05, 0x2b, 0x81, 0x04, 0x00, 0x22, 0x03, 0x62, 0x00
).toByteArrayOfBytes()

private fun IntArray.toByteArrayOfBytes(): ByteArray = ByteArray(size) { this[it].toByte() }

// endregion

// region CoreFoundation helpers

private fun ByteArray.toCFData(): CFDataRef? = usePinned { pinned ->
    CFDataCreate(
        kCFAllocatorDefault,
        if (isEmpty()) null else pinned.addressOf(0).reinterpret(),
        size.convert()
    )
}

private fun ByteArray.toSecCertificate(): SecCertificateRef? {
    val data = toCFData() ?: return null
    try {
        return SecCertificateCreateWithData(null, data)
    } finally {
        data.release()
    }
}

private fun CFDataRef.toByteArray(): ByteArray {
    val length = CFDataGetLength(this).toInt()
    if (length == 0) return ByteArray(0)
    val ptr = CFDataGetBytePtr(this) ?: return ByteArray(0)
    val bytes = ByteArray(length)
    bytes.usePinned { pinned ->
        memcpy(pinned.addressOf(0), ptr, length.convert())
    }
    return bytes
}

/** Decodes a CFString (passed as an opaque pointer) to a Kotlin String. */
private fun COpaquePointer.cfStringToKString(): String? {
    val cfString: CFStringRef = this.reinterpret()
    CFStringGetCStringPtr(cfString, kCFStringEncodingUTF8)?.let { return it.toKString() }
    return memScoped {
        val length = CFStringGetLength(cfString)
        val maxSize = CFStringGetMaximumSizeForEncoding(length, kCFStringEncodingUTF8) + 1
        val buffer = allocArray<ByteVar>(maxSize)
        if (CFStringGetCString(cfString, buffer, maxSize, kCFStringEncodingUTF8)) {
            buffer.toKString()
        } else {
            null
        }
    }
}

private fun CPointer<*>.release() {
    CFRelease(this)
}

// endregion

// region Minimal DER reader (just enough TLV walking to pull SAN dNSNames / CN out of a cert)

private object Asn1 {
    class Reader(private val bytes: ByteArray, private var pos: Int, private val end: Int) {
        constructor(bytes: ByteArray) : this(bytes, 0, bytes.size)

        fun hasMore(): Boolean = pos < end

        fun peekTag(): Int = if (pos < end) bytes[pos].toInt() and 0xFF else -1

        /** Reads tag + length, returns (tag, contentStart, contentEnd), and advances past content. */
        private fun readTlv(): Triple<Int, Int, Int> {
            require(pos < end) { "DER overrun" }
            val tag = bytes[pos++].toInt() and 0xFF
            require(pos < end) { "DER overrun" }
            var length = bytes[pos++].toInt() and 0xFF
            if (length and 0x80 != 0) {
                val byteCount = length and 0x7F
                length = 0
                repeat(byteCount) {
                    require(pos < end) { "DER overrun" }
                    length = (length shl 8) or (bytes[pos++].toInt() and 0xFF)
                }
            }
            val start = pos
            val contentEnd = start + length
            require(contentEnd in start..end) { "DER length out of bounds" }
            pos = contentEnd
            return Triple(tag, start, contentEnd)
        }

        fun skipElement() {
            readTlv()
        }

        fun readConstructed(expectedTag: Int): Reader {
            val (tag, start, contentEnd) = readTlv()
            require(tag == expectedTag) { "expected tag $expectedTag, got $tag" }
            return Reader(bytes, start, contentEnd)
        }

        fun readSequence(): Reader = readConstructed(0x30)

        /** Returns (tag, content bytes) of the next element. */
        fun readRaw(): Pair<Int, ByteArray> {
            val (tag, start, contentEnd) = readTlv()
            return tag to bytes.copyOfRange(start, contentEnd)
        }

        fun readOid(): String {
            val (_, start, contentEnd) = readTlv()
            return decodeOid(bytes, start, contentEnd)
        }

        private fun decodeOid(b: ByteArray, start: Int, end: Int): String {
            if (start >= end) return ""
            val sb = StringBuilder()
            val first = b[start].toInt() and 0xFF
            sb.append(first / 40).append('.').append(first % 40)
            var value = 0L
            for (i in (start + 1) until end) {
                val byte = b[i].toInt() and 0xFF
                value = (value shl 7) or (byte and 0x7F).toLong()
                if (byte and 0x80 == 0) {
                    sb.append('.').append(value)
                    value = 0L
                }
            }
            return sb.toString()
        }
    }
}

// endregion
