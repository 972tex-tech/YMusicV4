package kmphttp

import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import okio.IOException
import okio.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * One cross-platform [SslConfig] suite for the default [KmpHttpClient] on every engine
 * (OkHttp / NSURLSession / libcurl) — the whole point of the library: write the TLS contract once,
 * verify it everywhere. Network cases hit the real endpoints at https://badssl.com.
 *
 * Client certs are provided in BOTH supported formats ([SslConfig.ClientCert.Pkcs12] for
 * OkHttp/NSURLSession, [SslConfig.ClientCert.Pem] for libcurl); each engine picks the variant it
 * supports. Fixtures embed badssl.com certs that must be refreshed when badssl rotates them:
 *   - pin:         openssl s_client -connect badssl.com:443 | openssl x509 -pubkey -noout \
 *                    | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl base64
 *   - self-signed: openssl s_client -connect self-signed.badssl.com:443 | openssl x509 -outform der | base64
 *   - client p12:  curl https://badssl.com/certs/badssl.com-client.p12   (password "badssl.com")
 *   - client pem:  openssl pkcs12 -in client.p12 -passin pass:badssl.com -legacy -nodes
 */
open class SslTest {

    /** Builds the client under test. Subclasses override to pin a specific engine. */
    protected open fun newClient(configure: KmpHttpClient.BaseBuilder<*>.() -> Unit = {}) =
        KmpHttpClient.create {
            defaultExecuteTimeout = 30.seconds
            configure()
        }

    /**
     * Whether to skip the mutual-TLS case. Only NSURLSession on **macOS** is affected: its
     * `SecPKCS12Import` imports into the login keychain and can block on a headless host (iOS is
     * transient, and OkHttp/libcurl don't touch the keychain). Subclasses on a keychain-free engine
     * override this to `false`.
     */
    protected fun skipMutualTls(): Boolean = isMacosHost()

    private fun get(url: String) = RequestBuilder().url(url).get().build()

    @Test
    fun rejectsTrustingNothing() {
        // Empty trustedCAs + no system CAs would trust nothing -> rejected at construction.
        assertFailsWith<IllegalArgumentException> { SslConfig(trustSystemDefaultCAs = false) }
    }

    @Test
    fun selfSignedRejectedByDefault() = runTest {
        assertFailsWith<IOException> {
            newClient().execute(get("https://self-signed.badssl.com/"))
        }
    }

    @Test
    fun selfSignedTrustedViaAnchor() = runTest {
        // Trust ONLY this cert (anchors-only): the canonical way to accept a specific self-signed
        // server (trustSystemDefaultCAs = false).
        val cert = SELF_SIGNED_DER.decodeBase64()!!
        val client = newClient {
            sslConfig = SslConfig(trustedCAs = listOf(cert), trustSystemDefaultCAs = false)
        }
        client.execute(get("https://self-signed.badssl.com/")).use { assertEquals(200, it.code) }
    }

    @Test
    open fun extraAnchorKeepsSystemDefaults() = runTest {
        // Add an unrelated cert as an EXTRA anchor while keeping system defaults; exercises the
        // merge path (merged keystore / anchors-only=false / CA bundle merge) without breaking a
        // normally-validating host.
        val cert = SELF_SIGNED_DER.decodeBase64()!!
        val client = newClient {
            sslConfig = SslConfig(trustedCAs = listOf(cert), trustSystemDefaultCAs = true)
        }
        client.execute(get("https://badssl.com/")).use { assertEquals(200, it.code) }
    }

    @Test
    fun correctPinConnects() = runTest {
        val client = newClient {
            sslConfig = SslConfig(pinCertificates = mapOf("badssl.com" to listOf(BADSSL_LEAF_PIN)))
        }
        client.execute(get("https://badssl.com/")).use { assertEquals(200, it.code) }
    }

    @Test
    fun wrongPinFails() = runTest {
        val bogusPin = "sha256/" + ByteArray(32).toByteString().base64()
        val client = newClient {
            sslConfig = SslConfig(pinCertificates = mapOf("badssl.com" to listOf(bogusPin)))
        }
        assertFailsWith<IOException> { client.execute(get("https://badssl.com/")) }
    }

    @Test
    open fun mutualTlsWithClientCert() = runTest {
        if (skipMutualTls()) return@runTest

        val client = newClient {
            sslConfig = SslConfig(
                clientCerts = listOf(
                    SslConfig.ClientCert.Pkcs12(CLIENT_P12.decodeBase64()!!, "badssl.com"),
                    SslConfig.ClientCert.Pem(
                        privateKey = CLIENT_KEY_PEM.encodeUtf8(),
                        certificateChain = CLIENT_CERT_PEM.encodeUtf8(),
                    ),
                )
            )
        }
        client.execute(get("https://client.badssl.com/")).use { assertEquals(200, it.code) }
    }

    private companion object {
        const val BADSSL_LEAF_PIN = "sha256/gMmmm0NeAsTPXOXc+MdUiukZBAnYN2Si1xSUTU3VKfQ="

        const val SELF_SIGNED_DER =
            "MIIDeTCCAmGgAwIBAgIJANG9z2pELVi5MA0GCSqGSIb3DQEBCwUAMGIxCzAJBgNVBAYTAlVTMRMwEQYDVQQIDApDYWxpZm9ybmlhMRYwFAYDVQQHDA1TYW4gRnJhbmNpc2NvMQ8wDQYDVQQKDAZCYWRTU0wxFTATBgNVBAMMDCouYmFkc3NsLmNvbTAeFw0yNjA2MjMyMTAwMjBaFw0yODA2MjIyMTAwMjBaMGIxCzAJBgNVBAYTAlVTMRMwEQYDVQQIDApDYWxpZm9ybmlhMRYwFAYDVQQHDA1TYW4gRnJhbmNpc2NvMQ8wDQYDVQQKDAZCYWRTU0wxFTATBgNVBAMMDCouYmFkc3NsLmNvbTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAMIE7PiM7gTCs9hQ1XBYzJMY61yoaEmwIrX5lZ6xKyx2PmzAS2BMTOqytMAPgLaw+XLJhgL5XEFdEyt/ccRLvOmULlA3pmccYYz2QULFRtMWhyefdOsKnRFSJiFzbIRMeVXk0WvoBj1IFVKtsyjbqv9u/2CVSndrOfEk0TG23U3AxPxTuW1CrbV8/q71FdIzSOciccfCFHpsKOo3St/qbLVytH5aohbcabFXRNsKEqveww9HdFxBIuGa+RuT5q0iBikusbpJHAwnnqP7i/dAcgCskgjZjFeEU4EFy+b+a1SYQCeFxxC7c3DvaRhBB0VVfPlkPz0sw6l865MaTIbRyoUCAwEAAaMyMDAwCQYDVR0TBAIwADAjBgNVHREEHDAaggwqLmJhZHNzbC5jb22CCmJhZHNzbC5jb20wDQYJKoZIhvcNAQELBQADggEBAH72SKnQ0c8OPw84ffmHD+sDHfK98xBTHUtCjHpa7dqnORfwtvEfdqdKT01YS/5ky+hPa+KjgMboXzcR1ti3vqQ9ExWhkfFBn6dPBk9vu5jXT7K2V92hQeyNHsUu84z0oCFkXJq/xjE3+aRj5UUsmrRU5CAh3xDZTXXv6/pEdZWUa/d6Srg8xBHwEBphvwLxZrhJcqfg+AxxQqepp25LyUfuSHU4e9qUFjnMtfEqkgI2ptxpM1H/3CwTt4edYjV4vOIMYpBaP6Hyv+fHe4RoeR509O310wmlsIVclOIE1dvaPJnuQF/JanxCbZKvVLbEQZ+vyl+EBPwUBUxIKv0+LAQ="

        const val CLIENT_P12 =
            "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"

        // Converted from badssl.com-client.p12 (password "badssl.com") via:
        //   openssl pkcs12 -in client.p12 -passin pass:badssl.com -legacy -nodes
        val CLIENT_CERT_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIEnTCCAoWgAwIBAgIJAMW4uH+j2grHMA0GCSqGSIb3DQEBCwUAMH4xCzAJBgNV
            BAYTAlVTMRMwEQYDVQQIDApDYWxpZm9ybmlhMRYwFAYDVQQHDA1TYW4gRnJhbmNp
            c2NvMQ8wDQYDVQQKDAZCYWRTU0wxMTAvBgNVBAMMKEJhZFNTTCBDbGllbnQgUm9v
            dCBDZXJ0aWZpY2F0ZSBBdXRob3JpdHkwHhcNMjYwNjIzMjEwMDE4WhcNMjgwNjIy
            MjEwMDE4WjBvMQswCQYDVQQGEwJVUzETMBEGA1UECAwKQ2FsaWZvcm5pYTEWMBQG
            A1UEBwwNU2FuIEZyYW5jaXNjbzEPMA0GA1UECgwGQmFkU1NMMSIwIAYDVQQDDBlC
            YWRTU0wgQ2xpZW50IENlcnRpZmljYXRlMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A
            MIIBCgKCAQEAxzdfEeseTs/rukjly6MSLHM+Rh0enA3Ai4Mj2sdl31x3SbPoen08
            utVhjPmlxIUdkiMG4+ffe7N+JtDLG75CaxZp9CxytX7kywooRBJsRnQhmQPca8MR
            WAJBIz+w/L+3AFkTIqWBfyT+1VO8TVKPkEpGdLDovZOmzZAASi9/sj+j6gM7AaCi
            DeZTf2ES66abA5pOp60Q6OEdwg/vCUJfarhKDpi9tj3P6qToy9Y4DiBUhOct4MG8
            w5XwmKAC+Vfm8tb7tMiUoU0yvKKOcL6YXBXxB2kPcOYxYNobXavfVBEdwSrjQ7i/
            s3o6hkGQlm9F7JPEuVgbl/Jdwa64OYIqjQIDAQABoy0wKzAJBgNVHRMEAjAAMBEG
            CWCGSAGG+EIBAQQEAwIHgDALBgNVHQ8EBAMCBeAwDQYJKoZIhvcNAQELBQADggIB
            ADdvVnQgNCSfeg0H+HXPzcjybcycgLVraYcx5PSbQlrQ2a4oymLhfOO25g0ZJp9W
            cl2kSJxfZWQ57WQ2yzc2lbRwX2/3WIYjcmzFapuJqCCxVRhedaS4tG0wBhjF5b9T
            eTJNzm6thCAsvEzypcka+plGtQw+AXPiS4GeajVVdLAOJkZHlmeGvBQoz+dzbluw
            NsAMIyIQ5MeGuOESWwuYuvR6miFo5bu2FtHdel869TaWooyAVOpJNKWLg1W9uJdG
            s22rINp/B6u8ttuPMrxM/UM4eEcYAaHBjDh+pPgobUzF9TDUNWBZc2SGhW84EfU5
            uHKh6D199v36WUFw5UFf2pl8h4pKSp+ZbyD4ApZ87mKQR9/QnzMbB+vwPTstrs/5
            DiCAXwT5uvma2pj3PJmVXyr7P8KjZljns5o0FgBu8SrDKif9ARYneFx0FjekQx+L
            Epu7bvvUAxETUNbUSjtS3p8c5gNprt3bOANHzPpP2x0NDnAtQxcLM2QJlh+HpwaJ
            WgwHAaLraXpqThZfv+a6v9eZjVe8xeT0YQgC7F2isyN0vXlyspJcUNhReckbgBFV
            uQWpSGL5EnPYJqMXhPbNFvXEcH8byukF2Uc9jAYdrF/pkSY1vJwQLhTsFKzaFT30
            bab+MBKuFvi2jD44JLXnkFzUx4jxEvkgqDRZ6HMLp40J
            -----END CERTIFICATE-----
        """.trimIndent()

        val CLIENT_KEY_PEM = """
            -----BEGIN PRIVATE KEY-----
            MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDHN18R6x5Oz+u6
            SOXLoxIscz5GHR6cDcCLgyPax2XfXHdJs+h6fTy61WGM+aXEhR2SIwbj5997s34m
            0MsbvkJrFmn0LHK1fuTLCihEEmxGdCGZA9xrwxFYAkEjP7D8v7cAWRMipYF/JP7V
            U7xNUo+QSkZ0sOi9k6bNkABKL3+yP6PqAzsBoKIN5lN/YRLrppsDmk6nrRDo4R3C
            D+8JQl9quEoOmL22Pc/qpOjL1jgOIFSE5y3gwbzDlfCYoAL5V+by1vu0yJShTTK8
            oo5wvphcFfEHaQ9w5jFg2htdq99UER3BKuNDuL+zejqGQZCWb0Xsk8S5WBuX8l3B
            rrg5giqNAgMBAAECggEAVRB/t9b9igmeTlzyQpHPIMvUu3uTpm742JmWpcSe61FA
            XmhDzInNdLnIfbnb3p44kj4Coy5PbzKlm01sbNxA4BkiBPE1yen1J/2eU/LJ6QuN
            jRjo9drFfR75UWPQ3xu9uJhQY2rocLILXmvy69FlG+ebThh8SPbTMtNaTFMb47An
            pk2FrW9+rzPswbklOxls/SDt78usRvfAjslm73IdBTOrbceF+GmYs3/SXz1gu05p
            LxY2rhC8piBlqnD/QbXBahZbhjb9SkDFn2typMFZKkJIIKDJaOI2E9tIlZ97/0nZ
            txqchMty8IuU9YYAfLXCmj2IEfnvLtL7thLfKLuWAQKBgQDyXBpEgKFzfy2a1AI0
            +1qL/u5UN14l7S6/wmyDTgVMXwoxhwPRXWD5PutQ8D6tMfC/y4AYt3OXg1blCvLD
            XysNj5SK+dpmQR0SyeWjd9zwxJAXvx0McJefCYd86YGcGhJsuX5bkHIeQlEc6df7
            yoqr1480VQx/+Fk1i6Zr0EIUFQKBgQDSbalUOfXZh2EVRQEgf3VoPlxAiwGGQcVT
            i+pbjMG3pOwmkVyJZusGtN5HN4Oi7n1oiyfMYGsszKQ5j4TDBGS70pNUzhTv3Vn8
            0Vsfz0arJRqJxviiv4FfDmsYXwObNKwOjR+LEn1NUPkOYOLdz1lDuWOu11LE90Dy
            Q6hg8WwCmQKBgQDTy5lI9AAjpqh7/XpQQrhGT2qHPjuQeU25Vnbt6GjI7OVDkvHL
            LQdpyYprGQgs4s+5TGWNNARYC/cMAh1Ujv5Yw3jUWrR5V73IhZeg20bBQYWKuwDv
            thVKblFw377cZAxl51R9QCX6O4oW8mRFLiMxORd0bD6YNrf/CyNMZJraYQKBgAE7
            o0JbFJWxtV/qh5cpKAb0VpYKOngO6pkSuMzQhlINJVUUhPZJJBdl9+dy69KIkzOJ
            nTIVXotkp5GuxZhe7jgrg7F7g6PkKCLTFzWYgVF/ZihoggxyEs/7xaTe6aZ/KILt
            UMH/2bwaPVtYNfwWuu8qpurfWBzPVhIVU2c+AuQBAoGAXMbw10vyiznlhyMFw5kx
            SzlBMqJBLJkzQBtpvXuT0lqqxTSNC3N4WxgVOLCHa6HqXiB0790YL8/RWunsXTk2
            c7ugThP6iMPNVAycWkIF4vvHTwZ9RCSmEQabRaqGGLz/bhLL3fi3lPGCR+iW2Dxq
            GTH3fhaM/pZZGdIC75x/69Y=
            -----END PRIVATE KEY-----
        """.trimIndent()
    }
}
