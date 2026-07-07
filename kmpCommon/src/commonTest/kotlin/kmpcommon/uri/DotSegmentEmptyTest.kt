package kmpcommon.uri

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RFC 3986 §5.2.4 removes only "." and ".." segments; empty segments (consecutive slashes) are
 * significant and must survive. The old segment-collapsing implementation rewrote "//" to "/",
 * changing which resource a resolve() against such a base targets.
 */
class DotSegmentEmptyTest {

    @Test
    fun canonical_path_keeps_empty_segments() {
        assertEquals("/a//b", Uri.parse("http://h/a//b").canonicalPath.toString())
        assertEquals("/a/b//", Uri.parse("http://h/a/b//").canonicalPath.toString())
        assertEquals("//a", Uri.parse("http://h//a").canonicalPath.toString())
    }

    @Test
    fun dot_segments_still_removed_around_empties() {
        assertEquals("/a//b", Uri.parse("http://h/a//./b").canonicalPath.toString())
        assertEquals("/a/b", Uri.parse("http://h/a//../b").canonicalPath.toString())
    }

    @Test
    fun resolve_against_a_double_slash_base_targets_the_right_path() {
        val base = Uri.parse("http://a/x//y/z")
        assertEquals("http://a/x//y/w", base.resolve("w").toString())
    }
}
