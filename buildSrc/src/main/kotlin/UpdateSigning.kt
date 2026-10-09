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

/**
 * The macOS code-signing identity, e.g. `Developer ID Application: NAME (TEAMID)`, from
 * YMUSIC_MAC_IDENTITY or `mac.signing.identity` in local.properties. Empty leaves builds unsigned
 * — which is fine for local runs and impossible to ship, so it is never defaulted to a guess.
 */
fun Project.macSigningIdentity(): String =
    System.getenv("YMUSIC_MAC_IDENTITY") ?: localProp("mac.signing.identity") ?: ""

/** Apple ID for notarization (`mac.notarization.appleId`). */
fun Project.macNotarizationAppleId(): String =
    System.getenv("YMUSIC_MAC_APPLE_ID") ?: localProp("mac.notarization.appleId") ?: ""

/** App-specific password for that Apple ID (`mac.notarization.password`) — NOT the account
 *  password; generate one at appleid.apple.com. */
fun Project.macNotarizationPassword(): String =
    System.getenv("YMUSIC_MAC_APPLE_PASSWORD") ?: localProp("mac.notarization.password") ?: ""

/** Team ID the identity belongs to (`mac.notarization.teamId`). */
fun Project.macTeamId(): String =
    System.getenv("YMUSIC_MAC_TEAM_ID") ?: localProp("mac.notarization.teamId") ?: ""

/** Where installed copies look for releases (`update.manifest.url`). Empty disables update checks
 *  — with nothing to check against, not checking beats trusting the network. */
fun Project.updateManifestUrl(): String =
    System.getenv("YMUSIC_UPDATE_MANIFEST_URL") ?: localProp("update.manifest.url") ?: ""

/**
 * The ECDSA P-256 PUBLIC key that authenticates remote JS bundles (engineJs / playerJs), base64
 * SubjectPublicKeyInfo, from YMUSIC_BUNDLE_PUBLIC_KEY or `bundle.signing.public.key`. Its private
 * half signs bundles offline and never reaches a build or the config server. Empty: the app trusts
 * no remote bundle and runs only on the ones it ships with.
 */
fun Project.bundleSigningPublicKey(): String =
    System.getenv("YMUSIC_BUNDLE_PUBLIC_KEY") ?: localProp("bundle.signing.public.key") ?: ""

/** Base url of the remote config service (`remote.config.url`), e.g. https://cfg.ymusic.io. Empty
 *  leaves the app on its compiled-in defaults and makes no request. */
fun Project.remoteConfigUrl(): String =
    System.getenv("YMUSIC_REMOTE_CONFIG_URL") ?: localProp("remote.config.url") ?: ""
