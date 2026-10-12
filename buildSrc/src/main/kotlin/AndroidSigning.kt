import org.gradle.api.Project
import java.io.File

class ReleaseSigning(val storeFile: File, val storePassword: String, val keyAlias: String, val keyPassword: String)

/**
 * The Android release keystore, from secrets.properties `android.signing.*` (env YMUSIC_ANDROID_*).
 * The store path is relative to the repo root; a PKCS12 key shares the store password, so
 * keyPassword defaults to it. Null when unset or the file is missing: release builds stay
 * debug-signed, installable for testing and never shippable.
 */
fun Project.androidReleaseSigning(): ReleaseSigning? {
    fun value(env: String, key: String): String? =
        (System.getenv(env) ?: localProp(key))?.trim()?.takeIf { it.isNotEmpty() }
    val path = value("YMUSIC_ANDROID_STORE_FILE", "android.signing.storeFile") ?: return null
    val storeFile = rootProject.file(path).takeIf { it.isFile } ?: return null
    val storePassword = value("YMUSIC_ANDROID_STORE_PASSWORD", "android.signing.storePassword") ?: return null
    val keyAlias = value("YMUSIC_ANDROID_KEY_ALIAS", "android.signing.keyAlias") ?: return null
    val keyPassword = value("YMUSIC_ANDROID_KEY_PASSWORD", "android.signing.keyPassword") ?: storePassword
    return ReleaseSigning(storeFile, storePassword, keyAlias, keyPassword)
}
