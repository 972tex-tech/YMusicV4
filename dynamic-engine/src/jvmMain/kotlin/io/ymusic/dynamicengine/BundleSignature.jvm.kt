package io.ymusic.dynamicengine

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

internal actual fun verifyEcdsaP256(publicKeySpki: ByteArray, message: ByteArray, signatureDer: ByteArray): Boolean =
    try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeySpki))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(message)
            verify(signatureDer)
        }
    } catch (_: GeneralSecurityException) {
        false
    }
