package io.ymusic.desktop.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The envelope every manifest the app trusts arrives in — releases ([UpdateManifest]).
 *
 * The transport is never the trust boundary: whatever these manifests name ends up being executed,
 * so each is signed with an Ed25519 key whose public half is compiled into the app, and the
 * signature covers the `payload` STRING exactly as transmitted — re-serializing it (key order,
 * whitespace) can never change what was signed.
 * ```
 * { "payload": "{…}", "signature": "<base64 Ed25519>" }
 * ```
 */
object SignedPayload {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Verify [manifestJson] against [publicKeyBase64] and return its parsed payload.
     *
     * @throws UpdateVerificationException if the envelope is malformed, unsigned, or the signature
     * does not match — never a silent skip: an unverified manifest is treated as hostile.
     */
    fun open(manifestJson: String, publicKeyBase64: String): JsonObject {
        val root = runCatching { json.parseToJsonElement(manifestJson).jsonObject }.getOrNull()
            ?: throw UpdateVerificationException("manifest is not a JSON object")
        val payload = root["payload"]?.asStringOrNull()
            ?: throw UpdateVerificationException("manifest has no payload")
        val signature = root["signature"]?.asStringOrNull()
            ?: throw UpdateVerificationException("manifest is not signed")
        if (!verify(payload, signature, publicKeyBase64)) {
            throw UpdateVerificationException("manifest signature does not match the pinned key")
        }
        return runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: throw UpdateVerificationException("payload is not a JSON object")
    }

    private fun verify(payload: String, signatureBase64: String, publicKeyBase64: String): Boolean =
        runCatching {
            val keyBytes = Base64.getDecoder().decode(publicKeyBase64)
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(keyBytes))
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(payload.toByteArray(Charsets.UTF_8))
                verify(Base64.getDecoder().decode(signatureBase64))
            }
        }.getOrDefault(false)
}

/** kotlinx-serialization throws on a non-string primitive; this keeps parse errors uniform. */
internal fun kotlinx.serialization.json.JsonElement.asStringOrNull(): String? =
    runCatching { (this as? JsonPrimitive)?.content }.getOrNull()?.takeIf { it.isNotEmpty() }
