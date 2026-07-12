package ymusic.player.webview

/**
 * Rewrites the fetched YouTube embed HTML so the injected `webview_ymusic` bundle runs **at
 * document-start, before any YouTube script**. Pure string manipulation (no I/O), shared by every
 * platform host — each then loads the result with a base URL (`loadDataWithBaseURL` on Android,
 * `loadHTMLString(baseURL:)` on Apple, a resource handler on JVM/JCEF). Ported from the legacy
 * `WebViewYtPlayer.shouldInterceptRequest` HTML rewrite.
 *
 * Three edits, in order:
 *  1. **Strip CSP** — neutralize the `Content-Security-Policy` `<meta http-equiv>` so our inline
 *     boot script is not blocked.
 *  2. **Neutralize script preloads** — defang `<link rel="preload" as="script">` so YouTube can't
 *     fetch its player scripts before the page agent's script hook is installed.
 *  3. **Insert the boot script** as the first child of `<head>` (fallback `<body>`, then prepend),
 *     so it executes before everything else in the document.
 */
object HtmlInjector {
    private val cspMeta = Regex(
        """(<meta[^>]*http-equiv=["'])content-security-policy(["'])""",
        RegexOption.IGNORE_CASE,
    )
    private val scriptPreload = Regex(
        """(<link\b[^>]*\bas=["'])script(["'])""",
        RegexOption.IGNORE_CASE,
    )
    private val headOpen = Regex("""<head\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val bodyOpen = Regex("""<body\b[^>]*>""", RegexOption.IGNORE_CASE)

    /** Returns [html] with CSP stripped, script preloads defanged, and [bootScript] injected first. */
    fun inject(html: String, bootScript: String): String {
        val stripped = neutralizeScriptPreloads(stripCsp(html))
        val tag = "<script>\n$bootScript\n</script>"
        return insertFirstInHead(stripped, tag)
    }

    /** Rename the CSP `http-equiv` value so the browser ignores the policy. */
    fun stripCsp(html: String): String =
        cspMeta.replace(html) { "${it.groupValues[1]}content-security-policy-disabled${it.groupValues[2]}" }

    /** Turn `as="script"` on preload links into an inert value so the script isn't prefetched. */
    fun neutralizeScriptPreloads(html: String): String =
        scriptPreload.replace(html) { "${it.groupValues[1]}script-blocked${it.groupValues[2]}" }

    private fun insertFirstInHead(html: String, tag: String): String {
        val anchor = headOpen.find(html) ?: bodyOpen.find(html)
        return if (anchor != null) {
            val at = anchor.range.last + 1
            html.substring(0, at) + tag + html.substring(at)
        } else {
            tag + html
        }
    }
}
