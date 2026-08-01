package ymusic.desktop

import kmphttp.KmpHttpClient
import kmphttp.RedirectOptions
import kmphttp.RequestBuilder
import kmphttp.create
import kmphttp.utf8String
import kotlinx.coroutines.runBlocking
import ymusic.ytmedia.proxy.LocalMediaProxy
import java.net.URLEncoder
import kotlin.system.exitProcess

/**
 * Experiment for the loopback media-proxy interception mechanism (docs/player/youtube-playback.md).
 * Stands up [LocalMediaProxy] on 127.0.0.1 and drives a few simple requests through it — the same
 * shape a WebView's rewritten `/ymusicForward` media request would take — proving:
 *   1. the proxy serves a Kotlin-synthesized response (/hello),
 *   2. the proxy reconstructs + forwards a request packed in the query (/ymusicForward), and
 *   3. it forwards an arbitrary real URL (google's 204 endpoint).
 *
 * Run: `./gradlew :desktopApp:localProxyExperiment`
 */
fun main() {
    val client = KmpHttpClient.create {}
    val proxy = LocalMediaProxy(client) { println("[proxy] $it") }
    proxy.start()

    val exit = runCatching {
        runBlocking {
            suspend fun get(url: String) = client.execute(
                RequestBuilder().url(url).get().build(),
                RedirectOptions(followRedirects = true, followSslRedirects = true),
            )

            // 1. Synthesized response.
            val hello = get("${proxy.origin}/hello")
            val helloBody = hello.body.utf8String()
            println("[exp] GET /hello           -> ${hello.code} \"$helloBody\"")
            val test1 = hello.code == 200 && helloBody == "hello from LocalMediaProxy"

            // 2. Forward path, self-contained: the proxy fetches its own /hello (exercises parse + forward
            //    + a second concurrent connection), the exact shape of a rewritten media request.
            val innerUrl = URLEncoder.encode("${proxy.origin}/hello", "UTF-8")
            val fwd = get("${proxy.origin}/ymusicForward?url=$innerUrl&method=GET")
            val fwdBody = fwd.body.utf8String()
            println("[exp] GET /ymusicForward    -> ${fwd.code} \"$fwdBody\"")
            val test2 = fwd.code == 200 && fwdBody == "hello from LocalMediaProxy"

            // 3. Forward an arbitrary real URL (proves it proxies out to the internet, not just itself).
            val realUrl = URLEncoder.encode("https://www.google.com/generate_204", "UTF-8")
            val real = runCatching { get("${proxy.origin}/ymusicForward?url=$realUrl&method=GET") }.getOrNull()
            val test3 = real?.code == 204
            println("[exp] forward real URL      -> ${real?.code ?: "n/a"} (expect 204)")

            println("[exp] RESULT synthesized=$test1 forward=$test2 realUrl=$test3")
            if (test1 && test2 && test3) 0 else 2
        }
    }.getOrElse {
        System.err.println("[exp] FAILED: ${it.message}")
        it.printStackTrace()
        3
    }

    proxy.stop()
    exitProcess(exit)
}
