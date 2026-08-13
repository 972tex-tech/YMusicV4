import org.gradle.api.Project
import java.util.Properties

private fun Project.localProperty(name: String): String? =
    rootProject.file("local.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.inputStream().use { load(it) } }.getProperty(name)
    }

/** Sentry project DSN baked into the app at build time, kept out of VCS: the SENTRY_DSN env var,
 *  else sentry.dsn in local.properties (gitignored). Empty keeps the SDK off — crashes go
 *  unreported. */
fun Project.sentryDsn(): String =
    System.getenv("SENTRY_DSN") ?: localProperty("sentry.dsn") ?: ""

/** Sentry org auth token for the build-time R8 mapping upload: the SENTRY_AUTH_TOKEN env var, else
 *  sentry.auth.token in local.properties (gitignored). Empty skips the upload — release events
 *  then stay obfuscated in Sentry. */
fun Project.sentryAuthToken(): String =
    System.getenv("SENTRY_AUTH_TOKEN") ?: localProperty("sentry.auth.token") ?: ""

/** The git commit an app build was cut from — stamped into the Sentry release so an event traces
 *  straight back to git history (no source-context upload needed while builds stay unobfuscated). */
fun Project.gitCommit(): String =
    runCatching {
        val process = ProcessBuilder("git", "rev-parse", "--short=10", "HEAD")
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().readText().trim().takeIf {
            process.waitFor() == 0 && it.isNotEmpty()
        }
    }.getOrNull() ?: "unknown"
