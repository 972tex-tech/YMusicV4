package io.ymusic.dynamicengine

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8

/**
 * The authenticity check for a remote JS bundle: an ECDSA P-256 / SHA-256 signature over
 * `"<name>:<version>:<sha256 hex of the file>"`, made offline with the bundle signing key.
 *
 * [name] keeps an engine signature from passing as a player one, and [version] keeps an old signed
 * file from being replayed under a newer version number. [publicKey] is the pinned key, base64
 * X.509 SubjectPublicKeyInfo; [signature] is base64 DER. Malformed input is a failed check.
 */
fun verifyBundleSignature(publicKey: String, name: String, version: Long, sha256Hex: String, signature: String): Boolean {
    val key = publicKey.decodeBase64() ?: return false
    val signatureDer = signature.decodeBase64() ?: return false
    if (key.size == 0 || signatureDer.size == 0) return false
    val message = "$name:$version:$sha256Hex".encodeUtf8()
    // ByteArray: every platform verifier (JCA, Security.framework) takes the inputs whole.
    return verifyEcdsaP256(key.toByteArray(), message.toByteArray(), signatureDer.toByteArray())
}

/** ECDSA P-256 with SHA-256 over [message]; false on any malformed key or signature. */
internal expect fun verifyEcdsaP256(publicKeySpki: ByteArray, message: ByteArray, signatureDer: ByteArray): Boolean
