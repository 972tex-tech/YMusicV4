import org.gradle.api.Project
import java.util.Properties

/** Sentry project DSN baked into the app at build time, kept out of VCS: the SENTRY_DSN env var,
 *  else sentry.dsn in local.properties (gitignored). Empty keeps the SDK off — crashes go
 *  unreported. */
fun Project.sentryDsn(): String =
    System.getenv("SENTRY_DSN")
        ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { file ->
            Properties().apply { file.inputStream().use { load(it) } }.getProperty("sentry.dsn")
        }
        ?: ""

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
