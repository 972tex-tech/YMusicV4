package io.ymusic.desktop.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * A local stand-in for the release/config service, so the update flow and the JS remote-config
 * flow can be exercised end to end before any of it exists in production.
 *
 * ```
 *   ./gradlew :desktopApp:mockUpdateServer -PmockArgs="8899 /path/to/artifacts"
 * ```
 *
 * Serves:
 *  - `GET /update.json`      the signed update manifest (see [UpdateManifest])
 *  - `GET /download/<name>`  a file from the artifact dir, or synthesised bytes when absent
 *  - `GET /js-config.json`   what a real [io.ymusic.dynamicengine.JsConfigProvider] would answer
 *
 * The signing key is generated on first run into `<artifactDir>/dev-update-key` and reused after,
 * so the public key stays stable across restarts. It prints the public key at startup: that value
 * is what a build would pin into the app, and it is DEV-ONLY — a real release key never touches
 * a developer machine unencrypted.
 */
object MockUpdateServer {

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.getOrNull(1) ?: "build/mock-update").apply { mkdirs() }
        val server = start(args.getOrNull(0)?.toIntOrNull() ?: 8899, dir, args.getOrNull(2)?.toIntOrNull() ?: 99)
        val base = "http://localhost:${server.address.port}"
        println("mock update server on $base")
        println("  manifest : $base/update.json")
        println("  js config: $base/js-config.json")
        println("  artifacts: ${dir.absolutePath}")
        println("  public key (pin this in the app for dev): ${File(dir, "dev-update-key").readLines()[0]}")
    }

    /**
     * Bind and serve, returning the running server so a caller can stop it. Pass port 0 for an
     * ephemeral port (tests: a fixed port makes a second server silently reuse the first).
     */
    fun start(port: Int, dir: File, version: Int): HttpServer {
        dir.mkdirs()
        val keys = loadOrCreateKeyPair(File(dir, "dev-update-key"))

        val server = HttpServer.create(InetSocketAddress(port), 0)
        // Resolved AFTER binding, so urls in the manifest name the port actually in use.
        val base = "http://localhost:${server.address.port}"

        server.createContext("/update.json") { ex ->
            // Artifacts are whatever is in the dir; absent ones are synthesised so the endpoint is
            // useful before any real build has been copied in.
            val artifacts = platformArtifacts(dir, version)
            val payload = buildString {
                append("""{"versionCode":$version,"versionName":"mock-$version",""")
                append(""""notes":"Mock release from MockUpdateServer",""")
                append(""""platforms":{""")
                append(
                    artifacts.entries.joinToString(",") { (key, f) ->
                        """"$key":{"url":"$base/download/${f.name}","sha256":"${sha256(f)}","size":"${f.length()}"}"""
                    },
                )
                append("}}")
            }
            val signature = Signature.getInstance("Ed25519").run {
                initSign(keys.private)
                update(payload.toByteArray())
                Base64.getEncoder().encodeToString(sign())
            }
            val manifest = """{"payload":"${payload.jsonEscape()}","signature":"$signature"}"""
            ex.respond(200, manifest.toByteArray(), "application/json")
        }

        server.createContext("/download/") { ex ->
            val name = ex.requestURI.path.substringAfterLast('/')
            val file = File(dir, name)
            if (!file.isFile) ex.respond(404, "no such artifact: $name".toByteArray(), "text/plain")
            else ex.respond(200, file.readBytes(), "application/octet-stream")
        }

        // The engine-js/webview-js remote config: same shape JsConfigProvider.fetchConfig expects
        // (a bundle url + its revision). An empty body means "nothing new", which is what the
        // production provider returns today.
        server.createContext("/js-config.json") { ex ->
            val bundle = File(dir, "engine_ymusic.js")
            val body = if (bundle.isFile) {
                """{"url":"$base/download/${bundle.name}","version":${System.getenv("MOCK_JS_VERSION") ?: "21"}}"""
            } else {
                "{}"
            }
            ex.respond(200, body.toByteArray(), "application/json")
        }

        server.executor = null
        server.start()
        return server
    }

    /** One artifact per platform key; a missing file is created with placeholder bytes so the
     *  manifest is always complete and the download path is exercisable. */
    private fun platformArtifacts(dir: File, version: Int): Map<String, File> =
        listOf("mac-x64", "mac-arm64", "windows-x64", "linux-x64").associateWith { key ->
            File(dir, "ymusic-$version-$key.zip").apply {
                if (!exists()) writeBytes("placeholder artifact for $key $version".toByteArray())
            }
        }

    private fun loadOrCreateKeyPair(file: File): KeyPair {
        if (file.isFile) {
            val lines = file.readLines()
            val pub = KeyFactory.getInstance("Ed25519")
                .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(lines[0])))
            val priv = KeyFactory.getInstance("Ed25519")
                .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(lines[1])))
            return KeyPair(pub, priv)
        }
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        file.writeText(
            Base64.getEncoder().encodeToString(pair.public.encoded) + "\n" +
                Base64.getEncoder().encodeToString(pair.private.encoded) + "\n",
        )
        return pair
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    private fun HttpExchange.respond(code: Int, body: ByteArray, contentType: String) {
        responseHeaders.add("Content-Type", contentType)
        sendResponseHeaders(code, body.size.toLong())
        responseBody.use { it.write(body) }
    }

    private fun String.jsonEscape(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
