import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    // Exports the app's config (main class, jars, JVM args) to Conveyor, the packaging tool that
    // builds self-updating installers for all three OSes — see conveyor.conf at the repo root.
    id("dev.hydraulic.conveyor") version "2.0"
}

val desktopVersion = "1.0.0"
// Conveyor reads the standard Gradle project version (compose packaging reads packageVersion).
version = desktopVersion

/** The first candidate JDK that carries jpackage — the marker for "full JDK, not a JBR/JRE". */
val fullJdkHome: String? = run {
    val candidates = listOfNotNull(
        providers.gradleProperty("ymusic.jdkHome").orNull,
        System.getenv("JAVA_HOME"),
        System.getProperty("java.home"),
        // macOS keeps its installs out of JAVA_HOME's reach; ask the OS for a 17+ one.
        runCatching {
            providers.exec { commandLine("/usr/libexec/java_home", "-v", "17+") }
                .standardOutput.asText.get().trim()
        }.getOrNull(),
    )
    candidates.firstOrNull { it.isNotBlank() && File(it, "bin/jpackage").exists() }
}

// The DSN is machine-local (see SentryProps in buildSrc), so it reaches the app as generated
// source rather than a checked-in constant. The release carries the git commit — that's how an
// event traces back to git history without a source-context upload.
val generateBuildConfig by tasks.registering {
    val dsn = sentryDsn()
    val release = "ymusic-desktop@$desktopVersion+${gitCommit()}"
    val outDir = layout.buildDirectory.dir("generated/buildconfig/kotlin")
    inputs.property("dsn", dsn)
    inputs.property("release", release)
    inputs.property("engineJsVersion", Versions.engineJsBackendVersion)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().asFile.resolve("BuildConfig.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            package io.ymusic.desktop

            internal const val SENTRY_DSN = "$dsn"
            internal const val SENTRY_RELEASE = "$release"

            // The jar's engine_ymusic.js resource revision (Versions.engineJsBackendVersion).
            internal const val ENGINE_JS_VERSION: Long = ${Versions.engineJsBackendVersion}L
            """.trimIndent() + "\n",
        )
    }
}

// The engine bundle ships inside the jar — JsSourceProvider's embed copy (version = the
// generated ENGINE_JS_EMBED_VERSION const),
// no compiled-in constant.
val engineJsBundle = configurations.create("engineJsBundle") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    "engineJsBundle"(project(":engine-js", "engineJs"))
}
val stageEngineJs by tasks.registering(Copy::class) {
    from(engineJsBundle) { rename { "engine_ymusic.js" } }
    into(layout.buildDirectory.dir("engineJsResources"))
}

// The injected WebView player script, PROD build (terser-minified, DEBUG=false), staged into the
// jar for the packaged app. webview-js is not a Gradle module — this drives its npm pipeline
// directly, into our build dir so the dev dist/ stays whatever the dev loop last produced (the
// runtime prefers the dist file when running from a checkout; the jar copy is the fallback).
// Needs node + webview-js/node_modules (npm install) on the build host, like :engine-js needs npx.
val stageWebViewJs by tasks.registering(Exec::class) {
    val outFile = layout.buildDirectory.file("webviewJsResources/webview_ymusic.js")
    workingDir = rootProject.file("webview-js")
    inputs.dir(rootProject.file("webview-js/src"))
    inputs.files(rootProject.file("webview-js/build.mjs"), rootProject.file("webview-js/package.json"))
    outputs.file(outFile)
    commandLine("node", "build.mjs", "--prod", "--out", outFile.get().asFile.absolutePath)
}

// JCEF's own OSR path (CefBrowserOsr) renders through a jogl GLCanvas; ours is ImageOsrBrowser
// (pure AWT image painting, in :player) and the windowed login browser uses no GL either —
// jogl+gluegen (~7MB) are dead weight. Excluded at configuration level because jcefmaven reaches
// this app through TWO declarations (:player's and ours) and jcef-api re-declares jogl itself.
// CefBrowserOsr stays on the classpath but is never instantiated.
configurations.all {
    exclude(group = "me.friwi", module = "jogl-all")
    exclude(group = "me.friwi", module = "gluegen-rt")
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        /** The desktop (JVM) product shell: builds the AppGraph (JCEF, engines, player) and boots
         *  the shared :commonUi App. `./gradlew :desktopApp:run`. */
        val jvmMain by getting {
            kotlin.srcDir(generateBuildConfig)
            dependencies {
                implementation(project(":commonUi"))
                implementation(project(":media-scanner"))
                // NOT currentOs: that bakes the BUILD HOST's skiko into the one shared classpath,
                // which is wrong for Conveyor's cross-platform packages. The per-machine variants
                // are declared in the machine configurations below; the Conveyor plugin adds the
                // host-matching one to the run classpath, so :run keeps working.
                implementation(compose.desktop.common)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)
                implementation(Libs.Jcef.maven)
                implementation(Libs.Jna.core)
                implementation(project(":telemetry")) // api: sentry-kotlin-multiplatform + SentryLogger
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

// Per-machine Compose runtimes (skiko natives) for Conveyor's cross-platform packages — these
// configurations come from the Conveyor plugin, which exports each to that machine's inputs.
dependencies {
    "macAmd64"(compose.desktop.macos_x64)
    "macAarch64"(compose.desktop.macos_arm64)
    "windowsAmd64"(compose.desktop.windows_x64)
    "linuxAmd64"(compose.desktop.linux_x64)
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
    mainClass.set("io.ymusic.desktop.NowPlayingProbeKt")
    args((providers.gradleProperty("npArgs").orNull ?: "").split(" ").filter { it.isNotBlank() })
}

tasks.register<JavaExec>("webviewProbe") {
    group = "verification"
    description = "Drive the JVM WebView YouTube engine headlessly and report whether playback advances."
    val jvmMainCompilation = kotlin.jvm().compilations.getByName("main")
    dependsOn(jvmMainCompilation.compileTaskProvider)
    classpath(jvmMainCompilation.output.allOutputs, jvmMainCompilation.runtimeDependencyFiles)
    mainClass.set("io.ymusic.desktop.WebViewProbeKt")
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
    mainClass.set("io.ymusic.desktop.SabrDownloadProbeKt")
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
    mainClass.set("io.ymusic.desktop.DownloadManagerProbeKt")
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
    mainClass.set("io.ymusic.desktop.CatalogueMainKt")
}

compose.desktop {
    application {
        mainClass = "io.ymusic.desktop.MainKt"
        // A FULL JDK is needed both by jpackage/jlink and by ProGuard (it links against the
        // platform classes — without them it can't even resolve java.lang.Object). The Gradle
        // daemon often runs on a JBR that has neither, so find one; -Pymusic.jdkHome overrides.
        fullJdkHome?.let { javaHome = it }
        // JCEF (the WebView YouTube host) needs AWT internals opened on the JBR/JDK.
        jvmArgs(
            "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
        )
        // Release packaging (packageReleaseDmg etc.) runs ProGuard SHRINK-ONLY: no obfuscation, no
        // optimization — dead-code removal is the point (material-icons-extended alone is 37MB of
        // mostly-unused icon classes). Reflection/JNI/ServiceLoader keeps: proguard-rules.pro.
        buildTypes.release.proguard {
            version.set("7.7.0")
            optimize.set(false)
            obfuscate.set(false)
            configurationFiles.from(project.file("proguard-rules.pro"))
        }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "YMusic"
            packageVersion = desktopVersion
            // jlink modules the default set misses but the runtime graph uses: java.sql
            // (sqlite-jdbc), jdk.unsupported (Unsafe — JNA/vlcj/JOGL/coroutines/okio),
            // java.management + java.logging (Sentry integrations, JNA), jdk.crypto.ec
            // (TLS for jcefmaven's first-run CEF download).
            modules("java.sql", "java.management", "java.logging", "jdk.unsupported", "jdk.crypto.ec")
        }
    }
}

// NOTE (byte-measured, 2026-08-14): jlink's --compress=2 (settable only reflectively — the
// compose plugin's compressionLevel has no public DSL) shrinks the runtime dir 67→45MB on disk
// but GROWS the DMG 72.2→76.1MiB: the DMG's zlib squeezes the raw jimage better than jlink's
// per-resource zip, which is opaque to it. Download size is the goal → runtime stays uncompressed.

// What Conveyor packages: the ProGuard-shrunk classpath (~45MB) instead of the raw one (~118MB)
// that the Conveyor plugin exports from the dependency configurations. Two deliberate details:
//  - skiko is dropped — each package gets its own platform build from the machine configurations
//    above, and the shrunk output only ever contains the BUILD HOST's.
//  - foreign-platform natives are NOT stripped out of the jars here. Conveyor's
//    extract-native-libraries does that per target; stripping to the host's would leave every
//    other platform's package without its sqlite/JNA natives.
val conveyorJars by tasks.registering(Copy::class) {
    dependsOn("proguardReleaseJars")
    from(layout.buildDirectory.dir("compose/tmp/main-release/proguard")) {
        exclude("skiko-awt-runtime-*.jar")
    }
    into(layout.buildDirectory.dir("conveyorJars"))
}

kotlin.sourceSets.getByName("jvmMain").resources.srcDir(files(layout.buildDirectory.dir("engineJsResources")).builtBy(stageEngineJs))
kotlin.sourceSets.getByName("jvmMain").resources.srcDir(files(layout.buildDirectory.dir("webviewJsResources")).builtBy(stageWebViewJs))
