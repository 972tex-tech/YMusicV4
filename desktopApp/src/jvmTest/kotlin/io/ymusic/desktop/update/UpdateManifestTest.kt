package io.ymusic.desktop.update

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The manifest is the trust boundary of the updater: anything that fails to verify against the
 *  pinned key must be REFUSED, never quietly treated as "no update available". */
class UpdateManifestTest {

    private val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val publicKey: String = Base64.getEncoder().encodeToString(keyPair.public.encoded)

    private fun sign(payload: String): String = Signature.getInstance("Ed25519").run {
        initSign(keyPair.private)
        update(payload.toByteArray())
        Base64.getEncoder().encodeToString(sign())
    }

    private fun payload(version: String = "1.0.1", url: String = "https://example.test/a.zip") = """
        {"version":"$version","notes":"Fixes things",
         "platforms":{"mac-x64":{"url":"$url","sha256":"AABB","size":"123"}}}
    """.trimIndent()

    private fun manifest(payload: String, signature: String = sign(payload)): String {
        val escaped = payload.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return """{"payload":"$escaped","signature":"$signature"}"""
    }

    @Test
    fun a_signed_manifest_yields_the_release_for_this_platform() {
        val release = UpdateManifest.parseVerified(manifest(payload()), publicKey, "mac-x64")!!
        assertEquals("1.0.1", release.version.toString())
        assertEquals("https://example.test/a.zip", release.url)
        assertEquals("aabb", release.sha256, "hashes compare lowercase")
        assertEquals(123L, release.sizeBytes)
    }

    @Test
    fun a_platform_with_no_build_is_absent_not_an_error() {
        assertNull(UpdateManifest.parseVerified(manifest(payload()), publicKey, "linux-x64"))
    }

    @Test
    fun a_tampered_payload_is_refused() {
        // The attacker keeps a valid signature but swaps the download url underneath it.
        val good = payload()
        val evil = payload(url = "https://evil.test/pwn.zip")
        val e = assertFailsWith<UpdateVerificationException> {
            UpdateManifest.parseVerified(manifest(evil, signature = sign(good)), publicKey, "mac-x64")
        }
        assertTrue(e.message!!.contains("signature"), e.message!!)
    }

    @Test
    fun a_manifest_signed_by_another_key_is_refused() {
        val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val otherPublic = Base64.getEncoder().encodeToString(other.public.encoded)
        assertFailsWith<UpdateVerificationException> {
            UpdateManifest.parseVerified(manifest(payload()), otherPublic, "mac-x64")
        }
    }

    @Test
    fun an_unsigned_manifest_is_refused() {
        val bare = """{"payload":"{\"version\":\"9.9.9\"}"}"""
        assertFailsWith<UpdateVerificationException> {
            UpdateManifest.parseVerified(bare, publicKey, "mac-x64")
        }
    }

    @Test
    fun garbage_is_refused_rather_than_crashing() {
        assertFailsWith<UpdateVerificationException> {
            UpdateManifest.parseVerified("not json at all", publicKey, "mac-x64")
        }
    }

    @Test
    fun versions_compare_numerically_not_lexically() {
        val v = { s: String -> Version.parse(s)!! }
        assertTrue(v("1.10.0") > v("1.9.0"), "10 > 9 despite the string order")
        assertEquals(0, v("1.2.0").compareTo(v("1.2")), "absent components read as 0")
        assertTrue(v("2.0.0") > v("1.99.99"))
        // A build suffix identifies the build; it must not order releases.
        assertEquals(0, v("1.0.1+deadbeef").compareTo(v("1.0.1")))
        assertNull(Version.parse("not-a-version"))
    }

    @Test
    fun the_platform_key_matches_the_manifest_vocabulary() {
        assertTrue(
            currentPlatformKey() in setOf("mac-x64", "mac-arm64", "windows-x64", "linux-x64", "linux-arm64"),
            "unexpected key: ${currentPlatformKey()}",
        )
    }
}
