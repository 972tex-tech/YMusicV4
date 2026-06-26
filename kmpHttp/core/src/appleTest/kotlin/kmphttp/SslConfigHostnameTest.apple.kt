package kmphttp

import kmphttp.internal.certHostNames
import kmphttp.internal.hostMatchesCert
import okio.ByteString.Companion.decodeBase64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Verifies hostname extraction (SAN dNSNames + CN fallback) and matching for self-signed trust. */
class SslConfigHostnameTest {

    @Test
    fun extractsSanDnsNames() {
        val der = SELF_SIGNED_SAN_DER.decodeBase64()!!.toByteArray()
        assertEquals(listOf("*.badssl.com", "badssl.com"), certHostNames(der))
    }

    @Test
    fun fallsBackToCommonNameWhenNoSan() {
        // This cert has only a subject CN (no SAN extension).
        val der = CN_ONLY_DER.decodeBase64()!!.toByteArray()
        assertEquals(listOf("kmphttp-rsa"), certHostNames(der))
    }

    @Test
    fun matchesExactAndWildcardHosts() {
        val names = listOf("*.badssl.com", "badssl.com")
        assertTrue(hostMatchesCert("self-signed.badssl.com", names))   // wildcard, one label
        assertTrue(hostMatchesCert("badssl.com", names))               // exact
        assertTrue(hostMatchesCert("SELF-SIGNED.BADSSL.COM", names))   // case-insensitive
    }

    @Test
    fun rejectsNonMatchingHosts() {
        val names = listOf("*.badssl.com", "badssl.com")
        assertFalse(hostMatchesCert("evil.com", names))
        assertFalse(hostMatchesCert("a.b.badssl.com", names))          // wildcard is one label only
        assertFalse(hostMatchesCert("badssl.com.evil.com", names))
        assertFalse(hostMatchesCert("self-signed.badssl.com", emptyList()))
    }

    private companion object {
        // self-signed.badssl.com leaf — SAN: *.badssl.com, badssl.com
        const val SELF_SIGNED_SAN_DER =
            "MIIDeTCCAmGgAwIBAgIJANG9z2pELVi5MA0GCSqGSIb3DQEBCwUAMGIxCzAJBgNVBAYTAlVTMRMwEQYDVQQIDApDYWxpZm9ybmlhMRYwFAYDVQQHDA1TYW4gRnJhbmNpc2NvMQ8wDQYDVQQKDAZCYWRTU0wxFTATBgNVBAMMDCouYmFkc3NsLmNvbTAeFw0yNjA2MjMyMTAwMjBaFw0yODA2MjIyMTAwMjBaMGIxCzAJBgNVBAYTAlVTMRMwEQYDVQQIDApDYWxpZm9ybmlhMRYwFAYDVQQHDA1TYW4gRnJhbmNpc2NvMQ8wDQYDVQQKDAZCYWRTU0wxFTATBgNVBAMMDCouYmFkc3NsLmNvbTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAMIE7PiM7gTCs9hQ1XBYzJMY61yoaEmwIrX5lZ6xKyx2PmzAS2BMTOqytMAPgLaw+XLJhgL5XEFdEyt/ccRLvOmULlA3pmccYYz2QULFRtMWhyefdOsKnRFSJiFzbIRMeVXk0WvoBj1IFVKtsyjbqv9u/2CVSndrOfEk0TG23U3AxPxTuW1CrbV8/q71FdIzSOciccfCFHpsKOo3St/qbLVytH5aohbcabFXRNsKEqveww9HdFxBIuGa+RuT5q0iBikusbpJHAwnnqP7i/dAcgCskgjZjFeEU4EFy+b+a1SYQCeFxxC7c3DvaRhBB0VVfPlkPz0sw6l865MaTIbRyoUCAwEAAaMyMDAwCQYDVR0TBAIwADAjBgNVHREEHDAaggwqLmJhZHNzbC5jb22CCmJhZHNzbC5jb20wDQYJKoZIhvcNAQELBQADggEBAH72SKnQ0c8OPw84ffmHD+sDHfK98xBTHUtCjHpa7dqnORfwtvEfdqdKT01YS/5ky+hPa+KjgMboXzcR1ti3vqQ9ExWhkfFBn6dPBk9vu5jXT7K2V92hQeyNHsUu84z0oCFkXJq/xjE3+aRj5UUsmrRU5CAh3xDZTXXv6/pEdZWUa/d6Srg8xBHwEBphvwLxZrhJcqfg+AxxQqepp25LyUfuSHU4e9qUFjnMtfEqkgI2ptxpM1H/3CwTt4edYjV4vOIMYpBaP6Hyv+fHe4RoeR509O310wmlsIVclOIE1dvaPJnuQF/JanxCbZKvVLbEQZ+vyl+EBPwUBUxIKv0+LAQ="

        // Self-signed RSA cert with subject CN=kmphttp-rsa and NO SAN extension.
        const val CN_ONLY_DER =
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
    }
}
