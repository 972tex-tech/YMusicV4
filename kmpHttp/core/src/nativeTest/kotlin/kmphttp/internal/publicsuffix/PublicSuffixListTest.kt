package kmphttp.internal.publicsuffix

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PublicSuffixListTest {

    @Test
    fun testDafsaDataLoaded() {
        val MAGIC = ".DAFSA@PSL_"
        val magic = PSL_DAFSA.asSequence().map { (it.toInt() and 0xFF).toChar() }
            .take(MAGIC.length).joinToString("")
        assertEquals(MAGIC, magic, "DAFSA should have valid magic header")
    }

    @Test
    fun testDafsaLookupDirect() {
        // Test direct DAFSA lookup
        val resultCom = lookupStringInDafsa(PSL_DAFSA, "com")
        assertTrue(resultCom != DAFSA_NOT_FOUND, "com should be found in DAFSA")

        val resultOrg = lookupStringInDafsa(PSL_DAFSA, "org")
        assertTrue(resultOrg != DAFSA_NOT_FOUND, "org should be found in DAFSA")
    }

    @Test
    fun testGetPublicSuffix() {
        assertEquals("com", PublicSuffixList.getPublicSuffix("example.com"))
        assertEquals("com", PublicSuffixList.getPublicSuffix("www.example.com"))
        assertEquals("com.vn", PublicSuffixList.getPublicSuffix("www.example.com.vn"))
    }

    @Test
    fun testGetRegistrableDomain() {
        assertEquals("example.com", PublicSuffixList.getEffectiveTldPlusOne("example.com"))
        assertEquals("example.com", PublicSuffixList.getEffectiveTldPlusOne("www.example.com"))
        assertEquals("example.com", PublicSuffixList.getEffectiveTldPlusOne("sub.www.example.com"))
    }

    // Test cases from https://www.npmjs.com/package/psl
    // psl.get() is equivalent to getRegistrableDomain()

    @Test
    fun testMixedCase() {
        assertEquals(null, PublicSuffixList.getEffectiveTldPlusOne("COM"))
        assertEquals("example.COM", PublicSuffixList.getEffectiveTldPlusOne("example.COM"))
        assertEquals("example.COM", PublicSuffixList.getEffectiveTldPlusOne("WwW.example.COM"))
    }

    @Test
    fun testUnlistedTLD() {
        assertEquals(null, PublicSuffixList.getEffectiveTldPlusOne("example"))
        assertEquals("example.example", PublicSuffixList.getEffectiveTldPlusOne("example.example"))
        assertEquals("example.example", PublicSuffixList.getEffectiveTldPlusOne("b.example.example"))
        assertEquals("example.example", PublicSuffixList.getEffectiveTldPlusOne("a.b.example.example"))
    }

    @Test
    fun testTLDWithOnly1Rule() {
        // TLD with only 1 rule (biz)
        assertEquals(null, PublicSuffixList.getEffectiveTldPlusOne("biz"))
        assertEquals("domain.biz", PublicSuffixList.getEffectiveTldPlusOne("domain.biz"))
        assertEquals("domain.biz", PublicSuffixList.getEffectiveTldPlusOne("b.domain.biz"))
        assertEquals("domain.biz", PublicSuffixList.getEffectiveTldPlusOne("a.b.domain.biz"))
    }

    @Test
    fun testTLDWith2LevelRules() {
        // TLD with some 2-level rules (uk.com)
        assertEquals(null, PublicSuffixList.getEffectiveTldPlusOne("uk.com"))
        assertEquals("example.uk.com", PublicSuffixList.getEffectiveTldPlusOne("example.uk.com"))
        assertEquals("example.uk.com", PublicSuffixList.getEffectiveTldPlusOne("b.example.uk.com"))
    }

    @Test
    fun testComplexTLD() {
        // More complex TLD (kobe.jp with wildcards and exceptions)
        assertEquals(null, PublicSuffixList.getEffectiveTldPlusOne("c.kobe.jp"))
        assertEquals("b.c.kobe.jp", PublicSuffixList.getEffectiveTldPlusOne("b.c.kobe.jp"))
        assertEquals("b.c.kobe.jp", PublicSuffixList.getEffectiveTldPlusOne("a.b.c.kobe.jp"))
        assertEquals("city.kobe.jp", PublicSuffixList.getEffectiveTldPlusOne("city.kobe.jp"))
        assertEquals("city.kobe.jp", PublicSuffixList.getEffectiveTldPlusOne("www.city.kobe.jp"))
    }

    @Test
    fun testIDNPunycode() {
        // IDN labels (punycoded)
        assertEquals("xn--85x722f.com.cn", PublicSuffixList.getEffectiveTldPlusOne("xn--85x722f.com.cn"))
        assertEquals("xn--85x722f.xn--55qx5d.cn", PublicSuffixList.getEffectiveTldPlusOne("xn--85x722f.xn--55qx5d.cn"))
        assertEquals("xn--85x722f.xn--55qx5d.cn", PublicSuffixList.getEffectiveTldPlusOne("www.xn--85x722f.xn--55qx5d.cn"))

        // Balinese script: ᬩᬮᬶ.id = xn--9tfky.id
        // DAFSA stores punycode version, so punycode lookup should work
        assertEquals("xn--9tfky.id", PublicSuffixList.getPublicSuffix("xn--9tfky.id"))
    }

    @Test
    fun testIDNUnicode() {
        // IDN labels (unicode) - 食狮 = xn--85x722f, 公司 = xn--55qx5d
        assertEquals("食狮.com.cn", PublicSuffixList.getEffectiveTldPlusOne("食狮.com.cn"))
        assertEquals("食狮.公司.cn", PublicSuffixList.getEffectiveTldPlusOne("食狮.公司.cn"))
        assertEquals("食狮.公司.cn", PublicSuffixList.getEffectiveTldPlusOne("www.食狮.公司.cn"))
    }

    @Test
    fun testWildcardElbAmazonaws() {
        // *.elb.amazonaws.com is in PSL - any subdomain is a public suffix
        // Check DAFSA lookup for elb.amazonaws.com
        val dafsaResult = lookupStringInDafsa(PSL_DAFSA, "elb.amazonaws.com")
        assertTrue(dafsaResult != DAFSA_NOT_FOUND, "elb.amazonaws.com should be found in DAFSA")
        assertTrue((dafsaResult and 2) != 0, "elb.amazonaws.com should have WILDCARD flag")

        // elb.amazonaws.com itself should return null (parent of wildcard entries)
        assertEquals(null, PublicSuffixList.getEffectiveTldPlusOne("elb.amazonaws.com"))
        // bar.elb.amazonaws.com IS a public suffix (matches *.elb.amazonaws.com wildcard)
        assertEquals(null, PublicSuffixList.getEffectiveTldPlusOne("bar.elb.amazonaws.com"))
        // foo.bar.elb.amazonaws.com has eTLD+1 = foo.bar.elb.amazonaws.com
        assertEquals("foo.bar.elb.amazonaws.com", PublicSuffixList.getEffectiveTldPlusOne("foo.bar.elb.amazonaws.com"))
    }
}
