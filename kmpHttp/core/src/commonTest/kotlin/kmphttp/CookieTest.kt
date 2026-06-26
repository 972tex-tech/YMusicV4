/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kmphttp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CookieTest {
    private val url = "https://example.com/".toHttpUrl()

    @Test
    fun simpleCookie() = runTestSkipBrowser {
        val cookie = parseCookie(url, "SID=31d4d96e407aad42")
        assertEquals("SID=31d4d96e407aad42; path=/", cookie.toString())
    }

    @Test
    fun noEqualsSign() = runTestSkipBrowser {
        assertNull(parseCookie(url, "foo"))
        assertNull(parseCookie(url, "foo; Path=/"))
    }

    @Test
    fun emptyName() = runTestSkipBrowser {
        assertNull(parseCookie(url, "=b"))
        assertNull(parseCookie(url, " =b"))
        assertNull(parseCookie(url, "\r\t \n=b"))
    }

    @Test
    fun spaceInName() = runTestSkipBrowser {
        assertEquals("a b", parseCookie(url, "a b=cd")!!.name)
    }

    @Test
    fun spaceInValue() = runTestSkipBrowser {
        assertEquals("c d", parseCookie(url, "ab=c d")!!.value)
    }

    @Test
    fun trimLeadingAndTrailingWhitespaceFromName() = runTestSkipBrowser {
        assertEquals("a", parseCookie(url, " a=b")!!.name)
        assertEquals("a", parseCookie(url, "a =b")!!.name)
        assertEquals("a", parseCookie(url, "\r\t \na\n\t \n=b")!!.name)
    }

    @Test
    fun emptyValue() = runTestSkipBrowser {
        assertEquals("", parseCookie(url, "a=")!!.value)
        assertEquals("", parseCookie(url, "a= ")!!.value)
        assertEquals("", parseCookie(url, "a=\r\t \n")!!.value)
    }

    @Test
    fun trimLeadingAndTrailingWhitespaceFromValue() = runTestSkipBrowser {
        assertEquals("", parseCookie(url, "a= ")!!.value)
        assertEquals("b", parseCookie(url, "a= b")!!.value)
        assertEquals("b", parseCookie(url, "a=b ")!!.value)
        assertEquals("b", parseCookie(url, "a=\r\t \nb\n\t \n")!!.value)
    }

    @Test
    fun invalidCharacters() = runTestSkipBrowser {
        assertNull(parseCookie(url, "a\u0000b=cd"))
        assertNull(parseCookie(url, "ab=c\u0000d"))
        assertNull(parseCookie(url, "a\u0001b=cd"))
        assertNull(parseCookie(url, "ab=c\u0001d"))
        assertNull(parseCookie(url, "a\u0009b=cd"))
        assertNull(parseCookie(url, "ab=c\u0009d"))
        assertNull(parseCookie(url, "a\u001fb=cd"))
        assertNull(parseCookie(url, "ab=c\u001fd"))
        assertNull(parseCookie(url, "a\u007fb=cd"))
        assertNull(parseCookie(url, "ab=c\u007fd"))
        assertNull(parseCookie(url, "a\u0080b=cd"))
        assertNull(parseCookie(url, "ab=c\u0080d"))
        assertNull(parseCookie(url, "a\u00ffb=cd"))
        assertNull(parseCookie(url, "ab=c\u00ffd"))
    }

    @Test
    fun domainAndPath() = runTestSkipBrowser {
        val cookie = parseCookie(url, "SID=31d4d96e407aad42; Path=/; Domain=example.com")
        assertEquals("example.com", cookie!!.domain)
        assertEquals("/", cookie.path)
        assertFalse(cookie.hostOnly)
        assertEquals("SID=31d4d96e407aad42; domain=example.com; path=/", cookie.toString())
    }

    @Test
    fun secureAndHttpOnly() = runTestSkipBrowser {
        val cookie = parseCookie(url, "SID=31d4d96e407aad42; Path=/; Secure; HttpOnly")
        assertTrue(cookie!!.secure)
        assertTrue(cookie.httpOnly)
        assertEquals("SID=31d4d96e407aad42; path=/; secure; httponly", cookie.toString())
    }

    @Test
    fun domainMatches() = runTestSkipBrowser {
        val cookie = parseCookie(url, "a=b; domain=example.com")
        assertTrue(cookie!!.matches("http://example.com".toHttpUrl()))
        assertTrue(cookie.matches("http://www.example.com".toHttpUrl()))
        assertFalse(cookie.matches("http://square.com".toHttpUrl()))
    }

    @Test
    fun domainMatchesNoDomain() = runTestSkipBrowser {
        val cookie = parseCookie(url, "a=b")
        assertTrue(cookie!!.matches("http://example.com".toHttpUrl()))
        assertFalse(cookie.matches("http://www.example.com".toHttpUrl()))
        assertFalse(cookie.matches("http://square.com".toHttpUrl()))
    }

    @Test
    fun domainMatchesIgnoresLeadingDot() = runTestSkipBrowser {
        val cookie = parseCookie(url, "a=b; domain=.example.com")
        assertTrue(cookie!!.matches("http://example.com".toHttpUrl()))
        assertTrue(cookie.matches("http://www.example.com".toHttpUrl()))
        assertFalse(cookie.matches("http://square.com".toHttpUrl()))
    }

    @Test
    fun domainIgnoredWithTrailingDot() = runTestSkipBrowser {
        val cookie = parseCookie(url, "a=b; domain=example.com.")
        assertTrue(cookie!!.matches("http://example.com".toHttpUrl()))
        assertFalse(cookie.matches("http://www.example.com".toHttpUrl()))
        assertFalse(cookie.matches("http://square.com".toHttpUrl()))
    }

    @Test
    fun hostOnly() = runTestSkipBrowser {
        assertTrue(parseCookie(url, "a=b")!!.hostOnly)
        assertFalse(parseCookie(url, "a=b; domain=example.com")!!.hostOnly)
    }

    @Test
    fun defaultPath() = runTestSkipBrowser {
        assertEquals("/foo", parseCookie("http://example.com/foo/bar".toHttpUrl(), "a=b")!!.path)
        assertEquals("/foo", parseCookie("http://example.com/foo/".toHttpUrl(), "a=b")!!.path)
        assertEquals("/", parseCookie("http://example.com/foo".toHttpUrl(), "a=b")!!.path)
        assertEquals("/", parseCookie("http://example.com/".toHttpUrl(), "a=b")!!.path)
    }

    @Test
    fun defaultPathIsUsedIfPathDoesntHaveLeadingSlash() = runTestSkipBrowser {
        assertEquals(
            "/foo",
            parseCookie("http://example.com/foo/bar".toHttpUrl(), "a=b; path=quux")!!.path
        )
        assertEquals(
            "/foo",
            parseCookie("http://example.com/foo/bar".toHttpUrl(), "a=b; path=")!!.path
        )
    }

    @Test
    fun pathAttributeDoesntNeedToMatch() = runTestSkipBrowser {
        assertEquals(
            "/quux",
            parseCookie("http://example.com/".toHttpUrl(), "a=b; path=/quux")!!.path
        )
        assertEquals(
            "/quux",
            parseCookie("http://example.com/foo/bar".toHttpUrl(), "a=b; path=/quux")!!.path
        )
    }

    @Test
    fun httpOnly() = runTestSkipBrowser {
        assertFalse(parseCookie(url, "a=b")!!.httpOnly)
        assertTrue(parseCookie(url, "a=b; HttpOnly")!!.httpOnly)
    }

    @Test
    fun secure() = runTestSkipBrowser {
        assertFalse(parseCookie(url, "a=b")!!.secure)
        assertTrue(parseCookie(url, "a=b; Secure")!!.secure)
    }

    @Test
    fun parseAll() = runTestSkipBrowser {
        val headers =
            HeadersBuilder()
                .add("Set-Cookie: a=b")
                .add("Set-Cookie: c=d")
                .build()
        val cookies = parseAllCookies(url, headers)
        assertEquals(2, cookies.size)
        assertEquals("a=b; path=/", cookies[0].toString())
        assertEquals("c=d; path=/", cookies[1].toString())
    }

    @Test
    fun builder() = runTestSkipBrowser {
        val cookie =
            CookieBuilder()
                .name("a")
                .value("b")
                .domain("example.com")
                .build()
        assertEquals("a", cookie.name)
        assertEquals("b", cookie.value)
        assertEquals("example.com", cookie.domain)
        assertEquals("/", cookie.path)
        assertFalse(cookie.secure)
        assertFalse(cookie.httpOnly)
        assertFalse(cookie.hostOnly)
        assertNull(cookie.sameSite)
    }

    @Test
    fun builderNameValidation() = runTestSkipBrowser {
        assertFailsWith<IllegalArgumentException> {
            CookieBuilder().name(" a ")
        }
    }

    @Test
    fun builderValueValidation() = runTestSkipBrowser {
        assertFailsWith<IllegalArgumentException> {
            CookieBuilder().value(" b ")
        }
    }

    @Test
    fun builderDomainValidation() = runTestSkipBrowser {
        assertFailsWith<IllegalArgumentException> {
            CookieBuilder().hostOnlyDomain("a/b")
        }
    }

    @Test
    fun builderDomain() = runTestSkipBrowser {
        val cookie =
            CookieBuilder()
                .name("a")
                .value("b")
                .hostOnlyDomain("squareup.com")
                .build()
        assertEquals("squareup.com", cookie.domain)
        assertTrue(cookie.hostOnly)
    }

    @Test
    fun builderPath() = runTestSkipBrowser {
        val cookie =
            CookieBuilder()
                .name("a")
                .value("b")
                .hostOnlyDomain("example.com")
                .path("/foo")
                .build()
        assertEquals("/foo", cookie.path)
    }

    @Test
    fun builderPathValidation() = runTestSkipBrowser {
        assertFailsWith<IllegalArgumentException> {
            CookieBuilder().path("foo")
        }
    }

    @Test
    fun builderSecure() = runTestSkipBrowser {
        val cookie =
            CookieBuilder()
                .name("a")
                .value("b")
                .hostOnlyDomain("example.com")
                .secure()
                .build()
        assertTrue(cookie.secure)
    }

    @Test
    fun builderHttpOnly() = runTestSkipBrowser {
        val cookie =
            CookieBuilder()
                .name("a")
                .value("b")
                .hostOnlyDomain("example.com")
                .httpOnly()
                .build()
        assertTrue(cookie.httpOnly)
    }

    @Test
    fun builderIpv6() = runTestSkipBrowser {
        val cookie =
            CookieBuilder()
                .name("a")
                .value("b")
                .domain("0:0:0:0:0:0:0:1")
                .build()
        assertEquals("::1", cookie.domain)
    }

    @Test
    fun emptySameSite() = runTestSkipBrowser {
        assertEquals("", parseCookie(url, "a=b; SameSite=")!!.sameSite)
        assertEquals("", parseCookie(url, "a=b; SameSite= ")!!.sameSite)
        assertEquals("", parseCookie(url, "a=b; SameSite=\r\t \n")!!.sameSite)
    }

    @Test
    fun spaceInSameSite() = runTestSkipBrowser {
        assertEquals("a b", parseCookie(url, "a=b; SameSite=a b")!!.sameSite)
    }

    @Test
    fun trimLeadingAndTrailingWhitespaceFromSameSite() = runTestSkipBrowser {
        assertEquals("", parseCookie(url, "a=b; SameSite= ")!!.sameSite)
        assertEquals("Lax", parseCookie(url, "a= b; SameSite= Lax")!!.sameSite)
        assertEquals("Lax", parseCookie(url, "a=b ; SameSite=Lax ;")!!.sameSite)
        assertEquals("Lax", parseCookie(url, "a=\r\t \nb\n; \rSameSite=\n \tLax")!!.sameSite)
    }

    @Test
    fun builderSameSiteTrimmed() = runTestSkipBrowser {
        val cookieBuilder =
            CookieBuilder()
                .name("a")
                .value("b")
                .domain("example.com")

        assertFailsWith<IllegalArgumentException> {
            cookieBuilder.sameSite(" a").build()
        }
        assertFailsWith<IllegalArgumentException> {
            cookieBuilder.sameSite("a ").build()
        }
        assertFailsWith<IllegalArgumentException> {
            cookieBuilder.sameSite(" a ").build()
        }

        // This should succeed
        cookieBuilder.sameSite("a").build()
    }

    @Test
    fun builderSameSiteLax() = runTestSkipBrowser {
        val cookie =
            CookieBuilder()
                .name("a")
                .value("b")
                .domain("example.com")
                .sameSite("Lax")
                .build()
        assertEquals("Lax", cookie.sameSite)
    }

    @Test
    fun builderSameSiteStrict() = runTestSkipBrowser {
        val cookie =
            CookieBuilder()
                .name("a")
                .value("b")
                .domain("example.com")
                .sameSite("Strict")
                .build()
        assertEquals("Strict", cookie.sameSite)
    }

    @Test
    fun builderSameSiteNoneDoesNotRequireSecure() = runTestSkipBrowser {
        val cookie =
            CookieBuilder()
                .name("a")
                .value("b")
                .domain("example.com")
                .sameSite("None")
                .build()
        assertEquals("None", cookie.sameSite)
    }

    @Test
    fun expiresDate() = runTestSkipBrowser {
        val cookie = parseCookie(url, "a=b; Expires=Wed, 09 Jun 2021 10:18:14 GMT")
        assertEquals(1623233894000L, cookie!!.expiresAt)
        assertTrue(cookie.persistent)
    }

    @Test
    fun expiresDateWithDashes() = runTestSkipBrowser {
        val cookie = parseCookie(url, "a=b; Expires=Wed, 09-Jun-2021 10:18:14 GMT")
        assertEquals(1623233894000L, cookie!!.expiresAt)
    }

    @Test
    fun expiresDateTwoDigitYear() = runTestSkipBrowser {
        // Year 21 should become 2021
        val cookie = parseCookie(url, "a=b; Expires=Wed, 09 Jun 21 10:18:14 GMT")
        assertEquals(1623233894000L, cookie!!.expiresAt)
    }

    @Test
    fun expiresDateTwoDigitYearPre2000() = runTestSkipBrowser {
        // Year 99 should become 1999
        val cookie = parseCookie(url, "a=b; Expires=Thu, 01 Jan 99 00:00:00 GMT")
        assertEquals(915148800000L, cookie!!.expiresAt)
    }

    @Test
    fun expiresDateInvalidIsIgnored() = runTestSkipBrowser {
        val cookie = parseCookie(url, "a=b; Expires=not-a-date")
        assertFalse(cookie!!.persistent)
    }

    @Test
    fun maxAgeTakesPrecedenceOverExpires() = runTestSkipBrowser {
        // max-age=0 should take precedence, resulting in immediate expiration
        val cookie = parseCookie(url, "a=b; Expires=Wed, 09 Jun 2099 10:18:14 GMT; Max-Age=0")
        assertEquals(Long.MIN_VALUE, cookie!!.expiresAt)
        assertTrue(cookie.persistent)
    }

    // ==================== Public Suffix Tests ====================
    // These tests require PSL support. JS/WasmJS must call setupCookiePslForTests() first.

    @Test
    fun domainIsPublicSuffix() = runTestSkipBrowser {
        setupCookiePslForTests()
        assertNull(parseCookie(url, "a=b; domain=com"))
        assertNull(parseCookie(url, "a=b; domain=net"))
        assertNull(parseCookie(url, "a=b; domain=org"))
        assertNotNull(parseCookie("https://www.example.com".toHttpUrl(), "a=b; domain=example.com"))
    }

    @Test
    fun domainIsMultiLevelPublicSuffix() = runTestSkipBrowser {
        setupCookiePslForTests()
        assertNull(parseCookie("https://foo.co.uk".toHttpUrl(), "a=b; domain=co.uk"))
        assertNull(parseCookie("https://foo.ac.uk".toHttpUrl(), "a=b; domain=ac.uk"))
        assertNull(parseCookie("https://foo.com.cn".toHttpUrl(), "a=b; domain=com.cn"))
        assertNotNull(parseCookie("https://example.co.uk".toHttpUrl(), "a=b; domain=example.co.uk"))
    }

    @Test
    fun wildcardPublicSuffix() = runTestSkipBrowser {
        setupCookiePslForTests()
        assertNull(parseCookie("https://user.github.io".toHttpUrl(), "a=b; domain=github.io"))
        val cookie = parseCookie("https://user.github.io".toHttpUrl(), "a=b")
        assertNotNull(cookie)
        assertEquals("user.github.io", cookie.domain)
    }

    @Test
    fun publicSuffixExceptionRule() = runTestSkipBrowser {
        setupCookiePslForTests()
        val cookie = parseCookie("https://www.ck".toHttpUrl(), "a=b; domain=www.ck")
        assertNotNull(cookie)
        assertEquals("www.ck", cookie.domain)
        assertNull(parseCookie("https://foo.ck".toHttpUrl(), "a=b; domain=ck"))
    }

    @Test
    fun idnPublicSuffix() = runTestSkipBrowser {
        setupCookiePslForTests()
        assertNull(parseCookie("https://example.xn--fiqs8s".toHttpUrl(), "a=b; domain=xn--fiqs8s"))
        assertNotNull(
            parseCookie(
                "https://example.xn--fiqs8s".toHttpUrl(),
                "a=b; domain=example.xn--fiqs8s"
            )
        )
    }

    @Test
    fun amazonawsElbPublicSuffix() = runTestSkipBrowser {
        setupCookiePslForTests()
        // *.elb.amazonaws.com is in PSL
        // bar.elb.amazonaws.com is a public suffix (parent of wildcard)
        assertNull(
            parseCookie(
                "https://foo.bar.elb.amazonaws.com".toHttpUrl(),
                "a=b; domain=bar.elb.amazonaws.com"
            )
        )
        assertNotNull(
            parseCookie(
                "https://foo.bar.elb.amazonaws.com".toHttpUrl(),
                "a=b; domain=foo.bar.elb.amazonaws.com"
            )
        )

        val cookie = parseCookie("https://foo.bar.elb.amazonaws.com".toHttpUrl(), "a=b")
        assertNotNull(cookie)
        assertEquals("foo.bar.elb.amazonaws.com", cookie.domain)
    }

    @Test
    fun japanPublicSuffix() = runTestSkipBrowser {
        setupCookiePslForTests()
        assertNull(parseCookie("https://example.jp".toHttpUrl(), "a=b; domain=jp"))
        assertNull(parseCookie("https://example.co.jp".toHttpUrl(), "a=b; domain=co.jp"))
        assertNull(parseCookie("https://example.ac.jp".toHttpUrl(), "a=b; domain=ac.jp"))
        assertNotNull(parseCookie("https://example.co.jp".toHttpUrl(), "a=b; domain=example.co.jp"))
    }

    @Test
    fun unicodeDomainPublicSuffix() = runTestSkipBrowser {
        setupCookiePslForTests()
        // IDNA (RFC 5892) allows Unicode letters from various scripts.
        // Chinese characters like 食 (U+98DF) are PVALID - legitimate script letters.

        // 食狮.com.cn - Chinese domain with ASCII TLD
        assertNotNull(parseCookie("https://食狮.com.cn".toHttpUrl(), "a=b; domain=食狮.com.cn"))
        assertNull(parseCookie("https://食狮.com.cn".toHttpUrl(), "a=b; domain=com.cn"))

        // 食狮.公司.cn - Chinese domain with Chinese second-level domain
        assertNotNull(parseCookie("https://食狮.公司.cn".toHttpUrl(), "a=b; domain=食狮.公司.cn"))
        assertNull(parseCookie("https://食狮.公司.cn".toHttpUrl(), "a=b; domain=公司.cn"))

        // www.食狮.公司.cn - subdomain of Chinese domain, stored as punycode
        val cookie = parseCookie("https://www.食狮.公司.cn".toHttpUrl(), "a=b; domain=食狮.公司.cn")
        assertNotNull(cookie)
        assertEquals("xn--85x722f.xn--55qx5d.cn", cookie.domain)
    }

    @Test
    fun domainWithNullCharacter() = runTestSkipBrowser {
        // IDNA (RFC 5892) explicitly DISALLOWS control characters (U+0000-U+001F).
        // NUL (U+0000) is a control character and is not valid in domain names,
        // even though Unicode letters (like Chinese) are allowed.
        // Invalid domain attribute is ignored - cookie becomes host-only.
        val cookie1 = parseCookie(url, "a=b; domain=example\u0000.com")
        assertNotNull(cookie1)
        assertEquals("example.com", cookie1.domain)
        assertTrue(cookie1.hostOnly)

        val cookie2 = parseCookie(url, "a=b; domain=\u0000example.com")
        assertNotNull(cookie2)
        assertEquals("example.com", cookie2.domain)
        assertTrue(cookie2.hostOnly)
    }
}
