package io.ymusic.dynamicengine

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyVerifySignature
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256

/** A P-256 SubjectPublicKeyInfo: a fixed 26-byte header, then the 65-byte uncompressed point. */
private const val P256_SPKI_BYTES = 91
private const val P256_POINT_BYTES = 65

@OptIn(ExperimentalForeignApi::class)
internal actual fun verifyEcdsaP256(publicKeySpki: ByteArray, message: ByteArray, signatureDer: ByteArray): Boolean {
    if (publicKeySpki.size != P256_SPKI_BYTES || message.isEmpty() || signatureDer.isEmpty()) return false
    // SecKeyCreateWithData wants the raw X9.63 point, not the SPKI wrapper.
    val point = publicKeySpki.copyOfRange(P256_SPKI_BYTES - P256_POINT_BYTES, P256_SPKI_BYTES)
    if (point[0] != 0x04.toByte()) return false
    val keyData = point.toCFData() ?: return false
    val messageData = message.toCFData()
    val signatureData = signatureDer.toCFData()
    val attributes = CFDictionaryCreateMutable(null, 2, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
    CFDictionarySetValue(attributes, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
    CFDictionarySetValue(attributes, kSecAttrKeyClass, kSecAttrKeyClassPublic)
    val key = SecKeyCreateWithData(keyData, attributes, null)
    try {
        if (key == null || messageData == null || signatureData == null) return false
        return SecKeyVerifySignature(key, kSecKeyAlgorithmECDSASignatureMessageX962SHA256, messageData, signatureData, null)
    } finally {
        key?.let { CFRelease(it) }
        CFRelease(attributes)
        CFRelease(keyData)
        messageData?.let { CFRelease(it) }
        signatureData?.let { CFRelease(it) }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toCFData(): CFDataRef? =
    usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), size.convert()) }
