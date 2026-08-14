import org.gradle.api.Project
import java.util.Properties

private fun Project.localProp(name: String): String? =
    rootProject.file("local.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.inputStream().use { load(it) } }.getProperty(name)
    }

/**
 * The Ed25519 PRIVATE key that signs update manifests (base64 PKCS#8), from the
 * YMUSIC_UPDATE_PRIVATE_KEY env var else `update.private.key` in local.properties (gitignored).
 *
 * Never commit it and never let it reach a build that publishes logs: whoever holds this key can
 * push an update to every installed copy. Empty means the release task refuses to sign rather than
 * emitting an unsigned manifest — an unsigned manifest is one clients must reject anyway.
 */
fun Project.updatePrivateKey(): String =
    System.getenv("YMUSIC_UPDATE_PRIVATE_KEY") ?: localProp("update.private.key") ?: ""

/**
 * The matching PUBLIC key, compiled into the app so it can authenticate manifests. Safe to commit,
 * but it lives beside the private one so a key rotation changes both in one place.
 *
 * A build with no key pinned leaves update checks disabled (the client has nothing to verify
 * against) rather than trusting whatever the network returns.
 */
fun Project.updatePublicKey(): String =
    System.getenv("YMUSIC_UPDATE_PUBLIC_KEY") ?: localProp("update.public.key") ?: ""
