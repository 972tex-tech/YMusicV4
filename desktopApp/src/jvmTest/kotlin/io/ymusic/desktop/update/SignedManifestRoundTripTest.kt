package io.ymusic.desktop.update

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The producer (`:desktopApp:signUpdateManifest`) and the consumer ([UpdateManifest]) must agree,
 * and nothing in the repo proves that unless a real signed manifest is read back. Skips when the
 * release tasks have not been run — the point is to catch a DIVERGENCE, not to force a build.
 */
class SignedManifestRoundTripTest {

    private val manifest = File("build/update/update.json")
    private val publicKey: String? = System.getenv("YMUSIC_UPDATE_PUBLIC_KEY")
        ?: File("build/update/dev-public-key.txt").takeIf { it.isFile }?.readText()?.trim()

    @Test
    fun a_manifest_written_by_the_release_task_verifies_and_names_a_real_artifact() {
        if (!manifest.isFile || publicKey.isNullOrBlank()) {
            println("skipped: run :desktopApp:signUpdateManifest and record its public key first")
            return
        }
        val release = UpdateManifest.parseVerified(manifest.readText(), publicKey, currentPlatformKey())
        assertNotNull(release, "the manifest carries no entry for ${currentPlatformKey()}")
        assertTrue(release.versionCode > 0, "versionCode must be a real release counter")
        assertEquals(64, release.sha256.length)

        // The artifact the task hashed is still on disk here, so the digest is checkable.
        val artifact = File("build/compose/binaries").walkTopDown()
            .firstOrNull { it.isFile && it.name == release.url.substringAfterLast('/') }
        if (artifact != null) {
            val digest = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(release.sha256, digest, "manifest hash does not describe ${artifact.name}")
        }
    }
}
