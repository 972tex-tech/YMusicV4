package ymusic.player.webview

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HtmlInjectorTest {
    @Test
    fun boot_script_is_the_first_node_in_head() {
        val html = "<html><head><title>yt</title></head><body></body></html>"
        val out = HtmlInjector.inject(html, "window.ymusicSessionId='42'")
        val headStart = out.indexOf("<head>") + "<head>".length
        val script = out.indexOf("<script>")
        val title = out.indexOf("<title>")
        assertTrue(script in headStart until title, "boot script must come right after <head>, before <title>")
        assertContains(out, "window.ymusicSessionId='42'")
    }

    @Test
    fun csp_meta_is_neutralized() {
        val html = """<head><meta http-equiv="Content-Security-Policy" content="default-src 'self'"></head>"""
        val out = HtmlInjector.stripCsp(html)
        assertFalse(
            Regex("""http-equiv=["']content-security-policy["']""", RegexOption.IGNORE_CASE).containsMatchIn(out),
            "the active CSP http-equiv must be gone",
        )
        assertContains(out, "content-security-policy-disabled")
    }

    @Test
    fun script_preloads_are_defanged_but_other_preloads_kept() {
        val html = """
            <link rel="preload" href="base.js" as="script">
            <link rel="preload" href="f.woff2" as="font">
        """.trimIndent()
        val out = HtmlInjector.neutralizeScriptPreloads(html)
        assertFalse(Regex("""as=["']script["']""").containsMatchIn(out), "script preload defanged")
        assertContains(out, """as="font"""") // font preload untouched
        assertContains(out, "script-blocked")
    }

    @Test
    fun falls_back_to_body_then_prepend() {
        val body = HtmlInjector.inject("<body>hi</body>", "X")
        assertTrue(body.indexOf("<script>") in ("<body>".length)..body.indexOf("hi"))

        val bare = HtmlInjector.inject("just text", "X")
        assertTrue(bare.startsWith("<script>"), "no head/body → prepend")
    }

    @Test
    fun case_insensitive_tags() {
        val out = HtmlInjector.inject("<HTML><HEAD></HEAD></HTML>", "X")
        assertContains(out, "<script>")
        assertTrue(out.indexOf("<script>") > out.indexOf("<HEAD>"))
    }
}
