package kmphttp

import kotlin.test.Test

/**
 * CacheControl.parse runs on every cacheable response's header on non-JVM. Malformed (or hostile)
 * Cache-Control values must not throw a non-IOException out of the cache path: indexOfNonWhitespace
 * returning -1 slipped past a `pos < length` guard into value[-1], and a missing close quote fed
 * substring(start, -1). Both were StringIndexOutOfBounds.
 */
class CacheControlParseCrashTest {

    private fun parse(value: String) =
        CacheControl.parse(HeadersBuilder().add("Cache-Control", value).build())

    @Test
    fun malformed_values_do_not_crash() {
        // Each of these threw StringIndexOutOfBoundsException before the fix.
        parse("max-age=")
        parse("private, max-age=\"60")
        parse("max-age=   ")
        parse("no-cache, ")
        parse("=")
        parse("max-age=\"")
    }

    @Test
    fun well_formed_values_still_parse() {
        val cc = parse("max-age=120, private, no-cache")
        require(cc.maxAgeSeconds == 120)
        require(cc.noCache)
    }
}
