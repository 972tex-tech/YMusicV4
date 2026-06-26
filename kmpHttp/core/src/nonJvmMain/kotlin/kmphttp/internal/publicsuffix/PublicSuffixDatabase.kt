package kmphttp.internal.publicsuffix

/**
 * A database of public suffixes provided by [publicsuffix.org][publicsuffix_org].
 *
 * [publicsuffix_org]: https://publicsuffix.org/
 */
expect object PublicSuffixDatabase {
    /**
     * Returns the effective top-level domain plus one (eTLD+1) by referencing the public suffix list.
     * Returns null if the domain is a public suffix or a private address.
     *
     * Here are some examples:
     *
     * ```kotlin
     * assertEquals("google.com", getEffectiveTldPlusOne("google.com"))
     * assertEquals("google.com", getEffectiveTldPlusOne("www.google.com"))
     * assertNull(getEffectiveTldPlusOne("com"))
     * assertNull(getEffectiveTldPlusOne("localhost"))
     * assertNull(getEffectiveTldPlusOne("mymacbook"))
     * ```
     *
     * @param domain A canonicalized domain. Unicode domains are automatically converted to punycode.
     */
    suspend fun getEffectiveTldPlusOne(domain: String): String?
}
