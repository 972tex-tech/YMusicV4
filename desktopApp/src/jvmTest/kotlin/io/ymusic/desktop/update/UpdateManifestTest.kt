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

    private fun payload(versionCode: Int = 2, url: String = "https://example.test/a.zip") = """
        {"versionCode":$versionCode,"versionName":"1.0.1","notes":"Fixes things",
         "platforms":{"mac-x64":{"url":"$url","sha256":"AABB","size":"123"}}}
    """.trimIndent()

    private fun manifest(payload: String, signature: String = sign(payload)): String {
        val escaped = payload.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return """{"payload":"$escaped","signature":"$signature"}"""
    }

    @Test
    fun a_signed_manifest_yields_the_release_for_this_platform() {
        val release = UpdateManifest.parseVerified(manifest(payload()), publicKey, "mac-x64")!!
        assertEquals(2, release.versionCode)
        assertEquals("1.0.1", release.versionName)
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
    fun only_the_version_code_decides_whether_an_update_exists() {
        val release = UpdateManifest.parseVerified(manifest(payload(versionCode = 5)), publicKey, "mac-x64")!!
        assertTrue(release.isNewerThan(4))
        assertTrue(!release.isNewerThan(5), "the same build is not an update")
        assertTrue(!release.isNewerThan(6), "a downgrade is never offered")
    }

    @Test
    fun a_payload_without_a_version_code_is_refused() {
        val noCode = """{"versionName":"1.0.1","platforms":{"mac-x64":{"url":"u","sha256":"a"}}}"""
        assertFailsWith<UpdateVerificationException> {
            UpdateManifest.parseVerified(manifest(noCode), publicKey, "mac-x64")
        }
    }

    @Test
    fun a_v2_manifest_carries_the_file_list_and_blob_base() {
        val v2 = """
            {"versionCode":3,"versionName":"1.0.2","manifestVersion":2,"platforms":{"mac-x64":{
              "installer":{"url":"https://e.test/a.dmg","sha256":"AA","size":"9"},
              "blobBaseUrl":"https://e.test/blobs",
              "files":[{"path":"app/x.jar","sha256":"BB","size":"5"}]}}}
        """.trimIndent()
        val release = UpdateManifest.parseVerified(manifest(v2), publicKey, "mac-x64")!!
        assertEquals("https://e.test/a.dmg", release.url, "the installer stays the fallback route")
        assertTrue(release.supportsFileLevelUpdate)
        assertEquals(1, release.files.size)
        assertEquals("bb", release.files.first().sha256, "hashes compare lowercase")
        assertEquals("https://e.test/blobs/bb", release.blobUrl(release.files.first()))
    }

    @Test
    fun a_v1_manifest_still_parses_as_installer_only() {
        val release = UpdateManifest.parseVerified(manifest(payload()), publicKey, "mac-x64")!!
        assertEquals("https://example.test/a.zip", release.url)
        assertTrue(!release.supportsFileLevelUpdate, "no files means installer-only")
    }

    @Test
    fun the_platform_key_matches_the_manifest_vocabulary() {
        assertTrue(
            currentPlatformKey() in setOf("mac-x64", "mac-arm64", "windows-x64", "linux-x64", "linux-arm64"),
            "unexpected key: ${currentPlatformKey()}",
        )
    }
}
