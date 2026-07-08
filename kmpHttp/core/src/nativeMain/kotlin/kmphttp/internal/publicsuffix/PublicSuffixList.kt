/*
 * Public Suffix List API
 *
 * A "public suffix" is one under which Internet users can directly register names.
 * Examples: com, co.uk, github.io
 *
 * See: https://publicsuffix.org/
 */
package kmphttp.internal.publicsuffix

import kmpcommon.subSequence
import kmphttp.internal.delimiterOffset
import kmphttp.internal.idn.Idna

/**
 * Public Suffix List implementation using embedded DAFSA data.
 *
 * Thread-safe and allocation-free for lookups.
 * Supports both unicode and punycode domains via IDNA conversion.
 */
object PublicSuffixList {

    /**
     * @param domain IDN-Punycode domain.
     */
    internal fun getPublicSuffix(domain: CharSequence): CharSequence? {
        var offset = 0
        var previousSuffix: CharSequence? = null
        while (offset < domain.length) {
            val suffix = domain.subSequence(offset)

            val result = lookupDomain(suffix)
            when {
                result == DAFSA_NOT_FOUND -> Unit
                (result and DAFSA_EXCEPTION_RULE) != 0 -> {
                    // Exception rule: return suffix without first label
                    val nextDot = suffix.delimiterOffset('.')
                    return if (nextDot + 1 < suffix.length) {
                        suffix.substring(nextDot + 1)
                    } else {
                        null
                    }
                }
                (result and DAFSA_WILDCARD_RULE) != 0 -> {
                    // Wildcard rule: include one more label
                    return previousSuffix ?: suffix
                }
                else -> return suffix // FOUND
            }

            offset = domain.delimiterOffset('.', startIndex = offset) + 1
            previousSuffix = suffix
        }

        // unknown TLDs, treat last label as public suffix
        return checkNotNull(previousSuffix)
    }

    /**
     * Get the registrable domain (eTLD+1) of a domain.
     *
     * @param domain Full domain (e.g., "www.example.co.uk", "www.例え.jp"), must be a valid domain.
     * @return Registrable domain (e.g., "example.co.uk") or null
     */
    fun getEffectiveTldPlusOne(domain: String): String? {
        val ascii = Idna.toAscii(domain) ?: return null
        val publicSuffix = getPublicSuffix(ascii) ?: return null
        if (publicSuffix.length == ascii.length) {
            return null // Domain is the public suffix itself
        }

        var stripedLabelCount = 0
        for (i in 0 until ascii.length - publicSuffix.length) {
            if (ascii[i] == '.') stripedLabelCount++
        }

        check(stripedLabelCount > 0)
        if (stripedLabelCount == 1) return domain

        stripedLabelCount-- // Plus one label before the public suffix

        var offset = 0
        while (stripedLabelCount-- > 0) {
            offset = domain.delimiterOffset('.', startIndex = offset) + 1
        }
        return domain.substring(offset)
    }

    /** Look up domain in DAFSA */
    private fun lookupDomain(domain: CharSequence): Int {
        // DAFSA stores domains in ASCII/punycode
        return lookupStringInDafsa(PSL_DAFSA, domain)
    }
}
