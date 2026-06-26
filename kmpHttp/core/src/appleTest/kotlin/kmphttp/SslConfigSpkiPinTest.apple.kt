package kmphttp

import kmphttp.internal.spkiSha256PinFromDer
import okio.ByteString.Companion.decodeBase64
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the Apple SPKI pin computation against pins computed independently with OpenSSL:
 *
 *   openssl x509 -in cert.pem -pubkey -noout | openssl pkey -pubin -outform der \
 *     | openssl dgst -sha256 -binary | openssl base64
 *
 * This is the oracle that proves [spkiSha256PinFromDer] matches OkHttp's `sha256/...` pin values
 * (including the per-key-type ASN.1 SubjectPublicKeyInfo header reconstruction).
 */
class SslConfigSpkiPinTest {

    @Test
    fun rsa2048SpkiPinMatchesOpenSsl() {
        val der = RSA_CERT_DER_B64.decodeBase64()!!.toByteArray()
        assertEquals("IceQ0AsgPhggF7Xk1abgUTmDmjmSSzCzPbqw7YE978w=", spkiSha256PinFromDer(der))
    }

    @Test
    fun ecP256SpkiPinMatchesOpenSsl() {
        val der = EC_CERT_DER_B64.decodeBase64()!!.toByteArray()
        assertEquals("r2XYKrxrcbT5vaTxTty7ty2jMf/4BRhhEPC0UZkdBis=", spkiSha256PinFromDer(der))
    }

    private companion object {
        const val RSA_CERT_DER_B64 =
            "MIIDDTCCAfWgAwIBAgIUZFqjUsUfl4Qdw1raxWbTaW3skaEwDQYJKoZIhvcNAQELBQAwFjEUMBIGA1UEAwwL" +
                "a21waHR0cC1yc2EwHhcNMjYwNjI1MDI0MTAxWhcNMzYwNjIyMDI0MTAxWjAWMRQwEgYDVQQDDAtrbXBo" +
                "dHRwLXJzYTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAK9zqNsYk3EWoHnZMANXWm0Hfd6e" +
                "rnpFYrBielvKTjAlrjOOwJBriz2VQvexX+eZ9OUS9CBsmcnZGqFI0RU3Hg/v1WwJe1JjN76+OBQ5Ggv" +
                "7W4HjDo74r6ITiwSAAA7Qm1ukglQpBB12DeAaqROPd3qNF+ep7mXtDcYAWk4FEcZDijAV5XDYjtl0QA" +
                "S9jmdxpUSZkO7BS2rFJk3B1dQmRZ5NhSGIsvb1tSaOP7EWMNOXI+VUyU9m+CXiutuJEF3BmWQMZZNOM" +
                "L4P/m5UJIuAZqlHQJfllUwL1/hSyGXkvH+0N/ti3+ZEVcEQW1D1xEKpM4ntugjYB8jD/tPQe7qOShkC" +
                "AwEAAaNTMFEwHQYDVR0OBBYEFFHmnSvDpv4SH4JoMDs5ni/Lc2vrMB8GA1UdIwQYMBaAFFHmnSvDpv4S" +
                "H4JoMDs5ni/Lc2vrMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEBAEV/81vd+yToUu3D" +
                "BZomy6sgDRynlmahc/RfUh6PAEpapyVbX6B5nIdT7GTidss1ct8czeBnwQDWbFS6hkn6yrexrui+R6Tu" +
                "Lp2nSvN49V9Z6DvUng0bFOwtJzkSfYc/9MEssJdb9wSI36UpIfSKI4nJv+oigIt0ClZN0d2bIK3Z14m" +
                "rffsyc0WBdZtj4/wPbpiU8T/04yhCdV3xQOPnGMO8lQvG991CbQw9M2B8V9HwjVbM5WEQ3xh8M+DmTe" +
                "yW6FJnj6rjIsZEzEpAvIzDj0XTN4ii2jSq8a2lswNF97XIjzjoXR4TzqAAaPhsOExdpe4S8HUmR9dZv" +
                "PFTuc2Nc+0="

        const val EC_CERT_DER_B64 =
            "MIIBgDCCASWgAwIBAgIUMLHYjRQw7bMdJPnyI4tSkNvOEycwCgYIKoZIzj0EAwIwFTETMBEGA1UEAwwKa21w" +
                "aHR0cC1lYzAeFw0yNjA2MjUwMjQxMDFaFw0zNjA2MjIwMjQxMDFaMBUxEzARBgNVBAMMCmttcGh0dHAt" +
                "ZWMwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAARigj9DVwYFNrXA9MET/7jrKFy0Q2u9Hd+q7PLz/PQ/" +
                "WHbj8CPB8mYiluIW5SQkG/uIOaHpDwqjpiUPU4fi3420o1MwUTAdBgNVHQ4EFgQUljNZsYbLcpdznhAr" +
                "isvB92IpQT0wHwYDVR0jBBgwFoAUljNZsYbLcpdznhArisvB92IpQT0wDwYDVR0TAQH/BAUwAwEB/zAK" +
                "BggqhkjOPQQDAgNJADBGAiEA/n6V3NTCXsKORba0/yFiKtkp4f0tO9rTq9TmVQIvNKECIQCwWmoZ0JfQ" +
                "7GuJtI1JWLy9DBIvqI+Nu5A/YY+wmFTn6A=="
    }
}
