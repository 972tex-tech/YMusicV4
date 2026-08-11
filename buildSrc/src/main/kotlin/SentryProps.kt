import org.gradle.api.Project
import java.util.Properties

/** Sentry credentials, kept out of VCS: env var first, then local.properties (gitignored). */
private fun Project.sentryProp(env: String, key: String): String =
    System.getenv(env)
        ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { file ->
            Properties().apply { file.inputStream().use { load(it) } }.getProperty(key)
        }
        ?: ""

/** Project DSN baked into the app at build time; empty keeps the SDK off — crashes go unreported. */
fun Project.sentryDsn(): String = sentryProp("SENTRY_DSN", "sentry.dsn")

/** Org auth token for source-context upload at build time; empty skips the upload. */
fun Project.sentryAuthToken(): String = sentryProp("SENTRY_AUTH_TOKEN", "sentry.auth.token")
