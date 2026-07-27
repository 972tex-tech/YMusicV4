package ymusic.playground

import kmphttp.KmpHttpClient
import kmphttp.RequestBuilder
import kmphttp.utf8String
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Playground-local `embedHtml` source for [ymusic.player.createWebViewPlayer]: a direct fetch of
 * the YouTube embed page with the rewrites (CSP strip, preload defang, `INJECT_JS_HERE` slot first
 * in `<head>`) applied here. The CANONICAL producer is the scraping engine's `yt/embed`
 * (engine-js `logic/yt/embed.ts` — remotely updatable, shared cookie identity); this is a
 * dev-harness duplicate so the playground can drive the player without booting an engine.
 */
fun labEmbedHtml(httpClient: KmpHttpClient): suspend (userAgent: String) -> String = { userAgent ->
    withContext(Dispatchers.Default) {
        val resp = httpClient.execute(
            RequestBuilder()
                .header("User-Agent", userAgent)
                .url("https://www.youtube.com/embed")
                .build(),
        )
        val html = try {
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            resp.body.utf8String()
        } finally {
            resp.close()
        }
        prepareLabEmbedHtml(html)
    }
}

private val cspMeta = Regex("""(<meta[^>]*http-equiv=["'])content-security-policy(["'])""", RegexOption.IGNORE_CASE)
private val scriptPreload = Regex("""(<link\b[^>]*\bas=["'])script(["'])""", RegexOption.IGNORE_CASE)
private val headOpen = Regex("""<head\b[^>]*>""", RegexOption.IGNORE_CASE)
private val bodyOpen = Regex("""<body\b[^>]*>""", RegexOption.IGNORE_CASE)

internal fun prepareLabEmbedHtml(html: String): String {
    val rewritten = html
        .let { cspMeta.replace(it) { m -> "${m.groupValues[1]}content-security-policy-disabled${m.groupValues[2]}" } }
        .let { scriptPreload.replace(it) { m -> "${m.groupValues[1]}script-blocked${m.groupValues[2]}" } }
    val tag = "<script>\nINJECT_JS_HERE\n</script>"
    val anchor = headOpen.find(rewritten) ?: bodyOpen.find(rewritten)
    return if (anchor != null) {
        val at = anchor.range.last + 1
        rewritten.substring(0, at) + tag + rewritten.substring(at)
    } else {
        tag + rewritten
    }
}
