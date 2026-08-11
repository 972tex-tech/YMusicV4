import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val desktopVersion = "1.0.0"

// The DSN is machine-local (see SentryProps in buildSrc), so it reaches the app as generated
// source rather than a checked-in constant. The release carries the git commit — that's how an
// event traces back to git history without a source-context upload.
val generateSentryConfig by tasks.registering {
    val dsn = sentryDsn()
    val release = "ymusic-desktop@$desktopVersion+${gitCommit()}"
    val outDir = layout.buildDirectory.dir("generated/sentry/kotlin")
    inputs.property("dsn", dsn)
    inputs.property("release", release)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().asFile.resolve("SentryConfig.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            package ymusic.desktop

            internal const val SENTRY_DSN = "$dsn"
            internal const val SENTRY_RELEASE = "$release"
            """.trimIndent() + "\n",
        )
    }
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        /** The desktop (JVM) product shell: builds the AppGraph (JCEF, engines, player) and boots
         *  the shared :commonUi App. `./gradlew :desktopApp:run`. */
        val jvmMain by getting {
            kotlin.srcDir(generateSentryConfig)
            dependencies {
                implementation(project(":commonUi"))
                implementation(project(":media-scanner"))
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)
                implementation(Libs.Jcef.maven)
                implementation(Libs.Jna.core)
                implementation(Libs.Sentry.kmp)
                implementation(project(":kmpHttp:core"))
                implementation(project(":yt-media"))
                implementation(Libs.Kotlinx.coroutinesCore)
            }
        }

        // Headless snapshot renders of shared components (visual dev checks, not assertions).
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Headless diagnostic for the WebView engine (see WebViewProbe.kt):
//   ./gradlew :desktopApp:webviewProbe -PprobeArgs="realized"   (or "headless")
// Manual check of the macOS Now Playing bridge (see NowPlayingProbe.kt):
//   ./gradlew :desktopApp:nowPlayingProbe   — then press media keys / use Control Center.
tasks.register<JavaExec>("nowPlayingProbe") {
    group = "verification"
    description = "Publish a Now Playing state and print every media command the OS delivers."
    val jvmMainCompilation = kotlin.jvm().compilations.getByName("main")
    dependsOn(jvmMainCompilation.compileTaskProvider)
    classpath(jvmMainCompilation.output.allOutputs, jvmMainCompilation.runtimeDependencyFiles)
    mainClass.set("ymusic.desktop.NowPlayingProbeKt")
    args((providers.gradleProperty("npArgs").orNull ?: "").split(" ").filter { it.isNotBlank() })
}

tasks.register<JavaExec>("webviewProbe") {
    group = "verification"
    description = "Drive the JVM WebView YouTube engine headlessly and report whether playback advances."
    val jvmMainCompilation = kotlin.jvm().compilations.getByName("main")
    dependsOn(jvmMainCompilation.compileTaskProvider)
    classpath(jvmMainCompilation.output.allOutputs, jvmMainCompilation.runtimeDependencyFiles)
    mainClass.set("ymusic.desktop.WebViewProbeKt")
    jvmArgs(
        "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
    )
    args((providers.gradleProperty("probeArgs").orNull ?: "realized").split(" "))
}

// End-to-end SABR download probe (see SabrDownloadProbe.kt):
//   ./gradlew :desktopApp:sabrDownload -PsabrArgs="<videoId>"
tasks.register<JavaExec>("sabrDownload") {
    group = "verification"
    description = "Play a video, extract its decoded player response + PO token, and SABR-download the audio."
    val jvmMainCompilation = kotlin.jvm().compilations.getByName("main")
    dependsOn(jvmMainCompilation.compileTaskProvider)
    classpath(jvmMainCompilation.output.allOutputs, jvmMainCompilation.runtimeDependencyFiles)
    mainClass.set("ymusic.desktop.SabrDownloadProbeKt")
    jvmArgs(
        "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
    )
    args((providers.gradleProperty("sabrArgs").orNull ?: "dQw4w9WgXcQ").split(" "))
}

// End-to-end Download Manager probe (see DownloadManagerProbe.kt):
//   ./gradlew :desktopApp:downloadManager -PsabrArgs="<videoId>"
tasks.register<JavaExec>("downloadManager") {
    group = "verification"
    description = "Enqueue MP3 + M4A downloads through the real DownloadService and verify the output files."
    val jvmMainCompilation = kotlin.jvm().compilations.getByName("main")
    dependsOn(jvmMainCompilation.compileTaskProvider)
    classpath(jvmMainCompilation.output.allOutputs, jvmMainCompilation.runtimeDependencyFiles)
    mainClass.set("ymusic.desktop.DownloadManagerProbeKt")
    jvmArgs(
        "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
    )
    args((providers.gradleProperty("sabrArgs").orNull ?: "dQw4w9WgXcQ").split(" "))
}

// Standalone UI-component catalogue (see CatalogueMain.kt): ./gradlew :desktopApp:catalogue
tasks.register<JavaExec>("catalogue") {
    group = "application"
    description = "Open the UI-component catalogue window (no engines/CEF — fast design loop)."
    val jvmMainCompilation = kotlin.jvm().compilations.getByName("main")
    dependsOn(jvmMainCompilation.compileTaskProvider)
    classpath(jvmMainCompilation.output.allOutputs, jvmMainCompilation.runtimeDependencyFiles)
    mainClass.set("ymusic.desktop.CatalogueMainKt")
}

compose.desktop {
    application {
        mainClass = "ymusic.desktop.MainKt"
        // JCEF (the WebView YouTube host) needs AWT internals opened on the JBR/JDK.
        jvmArgs(
            "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "YMusic"
            packageVersion = desktopVersion
        }
    }
}
