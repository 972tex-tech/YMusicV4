package io.ymusic.desktop.update

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.URI
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The mock service and the client must agree: what MockUpdateServer signs is exactly what
 *  [UpdateManifest] accepts, and the artifact it advertises is really downloadable. */
class MockUpdateServerTest {

    private val dir = File("build/test-mock-update-${System.nanoTime()}")
    private var server: HttpServer? = null

    /** Ephemeral port per test — a fixed one lets a second server silently serve the first's
     *  content (and the key mismatch then fakes a passing signature test). */
    private fun serve(): String {
        val s = MockUpdateServer.start(0, dir, 99).also { server = it }
        return fetch("http://localhost:${s.address.port}/update.json")
    }

    @AfterTest fun cleanup() {
        server?.stop(0)
        dir.deleteRecursively()
    }

    private fun fetch(url: String): String =
        URI(url).toURL().openStream().use { it.readBytes().decodeToString() }

    private fun devPublicKey(): String = File(dir, "dev-update-key").readLines()[0]

    @Test
    fun the_served_manifest_verifies_and_its_artifact_downloads() {
        val release = UpdateManifest.parseVerified(serve(), devPublicKey(), "mac-x64")!!

        assertEquals(99, release.versionCode)
        assertEquals(64, release.sha256.length, "sha256 is a full hex digest")

        // The advertised url really serves bytes, and the digest describes them.
        val bytes = URI(release.url).toURL().openStream().use { it.readBytes() }
        assertEquals(release.sizeBytes, bytes.size.toLong())
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(release.sha256, digest, "the manifest's hash must describe the served artifact")
    }

    @Test
    fun every_platform_the_app_runs_on_has_an_entry() {
        val manifest = serve()
        val key = devPublicKey()
        for (platform in listOf("mac-x64", "mac-arm64", "windows-x64", "linux-x64")) {
            assertTrue(
                UpdateManifest.parseVerified(manifest, key, platform) != null,
                "no release advertised for $platform",
            )
        }
    }

    @Test
    fun the_signature_is_bound_to_this_key_pair() {
        val manifest = serve()
        val otherKey = Base64.getEncoder().encodeToString(
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded,
        )
        assertFailsWith<UpdateVerificationException> {
            UpdateManifest.parseVerified(manifest, otherKey, "mac-x64")
        }
    }
}
