import org.gradle.api.Project
import java.util.Properties

private const val BUILD_SETTINGS = "ymusic.buildSettings"

/**
 * A build setting from local.properties (this machine: sdk.dir, dev server) or secrets.properties
 * (keys and passwords, sealed into secrets.enc); both gitignored, local.properties wins. Both are
 * read once per build, on the root project — not in a static, which the daemon would keep stale.
 */
internal fun Project.localProp(name: String): String? {
    val extra = rootProject.extensions.extraProperties
    if (!extra.has(BUILD_SETTINGS)) {
        extra.set(
            BUILD_SETTINGS,
            Properties().apply {
                for (fileName in listOf("secrets.properties", "local.properties")) {
                    rootProject.file(fileName).takeIf { it.isFile }?.inputStream()?.use { load(it) }
                }
            },
        )
    }
    return (extra.get(BUILD_SETTINGS) as Properties).getProperty(name)
}

/**
 * The ECDSA P-256 PUBLIC key app updates are signed with (base64 SubjectPublicKeyInfo), from
 * YMUSIC_UPDATE_PUBLIC_KEY or `update.public.key`, compiled into the app. Its private half
 * (`update.private.key`) signs installers offline with the config service's
 * scripts/sign-app-update.mjs and never reaches a build. Empty: no update is ever offered.
 */
fun Project.updatePublicKey(): String =
    System.getenv("YMUSIC_UPDATE_PUBLIC_KEY") ?: localProp("update.public.key") ?: ""

/**
 * The macOS code-signing identity, e.g. `Developer ID Application: NAME (TEAMID)`, from
 * YMUSIC_MAC_IDENTITY or `mac.signing.identity` in secrets.properties. Empty leaves builds unsigned
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

/**
 * The ECDSA P-256 PUBLIC key that authenticates remote JS bundles (engineJs / playerJs), base64
 * SubjectPublicKeyInfo, from YMUSIC_BUNDLE_PUBLIC_KEY or `bundle.signing.public.key`. Its private
 * half signs bundles offline and never reaches a build or the config server. Empty: the app trusts
 * no remote bundle and runs only on the ones it ships with.
 */
fun Project.bundleSigningPublicKey(): String =
    System.getenv("YMUSIC_BUNDLE_PUBLIC_KEY") ?: localProp("bundle.signing.public.key") ?: ""

/** Base url of the remote config service (`remote.config.url`), e.g. https://api.example.com. Empty
 *  leaves the app on its compiled-in defaults and makes no request. `-PuseLocalServerIp` overrides
 *  it with the server on this machine (see [localServerUrl]). */
fun Project.remoteConfigUrl(): String =
    localServerUrl() ?: System.getenv("YMUSIC_REMOTE_CONFIG_URL") ?: localProp("remote.config.url") ?: ""
