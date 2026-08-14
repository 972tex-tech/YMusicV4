import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val desktopVersion = "1.0.0"

// Android's BuildConfig.DEBUG equivalent, compile-time: true unless the build ran with
// -Pymusic.debugLogs=false — the ONE release knob, shared with :kmpCommon's BuildFlags (so a
// release package strips debug logs and flips these gates in the same build).
val debugBuild = providers.gradleProperty("ymusic.debugLogs").map(String::toBoolean).getOrElse(true)

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
    inputs.property("debugBuild", debugBuild)
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

            // Compile-time debug flag (Android's BuildConfig.DEBUG): false when the build ran
            // with -Pymusic.debugLogs=false — gates Sentry environment, CEF debug port, etc.
            internal const val DEBUG_BUILD: Boolean = $debugBuild
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
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)
                implementation(Libs.Jcef.maven) {
                    // JCEF's own OSR path (CefBrowserOsr) renders through a jogl GLCanvas; ours is
                    // ImageOsrBrowser (pure AWT image painting) and the windowed login browser uses
                    // no GL either — jogl+gluegen (~7MB) are dead weight. CefBrowserOsr stays on
                    // the classpath but is never instantiated (ImageOsrBrowser extends CefBrowser_N
                    // directly; only the dev NowPlayingProbe asks the factory for OSR).
                    exclude(group = "me.friwi", module = "jogl-all")
                    exclude(group = "me.friwi", module = "gluegen-rt")
                }
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
        // jpackage/jlink live in a full JDK; the Gradle daemon often runs on a JBR without them.
        // Point packaging at one via -Pymusic.jdkHome=<path> (defaults to the Gradle JVM).
        providers.gradleProperty("ymusic.jdkHome").orNull?.let { javaHome = it }
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

// sqlite-jdbc (~12MB of it) and JNA bundle natives for EVERY OS/arch; a package needs only the
// host OS's (packaging is per-OS anyway). Strip the rest from the ProGuard output jars — that's
// what jpackage consumes for the release tasks. zip exit 12 = "nothing matched", fine.
tasks.matching { it.name == "proguardReleaseJars" }.configureEach {
    val proguardOut = layout.buildDirectory.dir("compose/tmp/main-release/proguard")
    doLast {
        val os = org.gradle.internal.os.OperatingSystem.current()
        val sqliteKeep = if (os.isMacOsX) "Mac" else if (os.isLinux) "Linux" else "Windows"
        val jnaKeep = if (os.isMacOsX) "darwin" else if (os.isLinux) "linux" else "win32"
        // Dash-suffixed on purpose: com/sun/jna/win32/ (no dash) is a CLASS package, not natives.
        val jnaOses = listOf("aix", "darwin", "dragonflybsd", "freebsd", "linux", "openbsd", "sunos", "win32")
        fun strip(jar: java.io.File, patterns: List<String>) {
            val p = ProcessBuilder(listOf("zip", "-q", "-d", jar.absolutePath) + patterns)
                .redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().decodeToString()
            check(p.waitFor() in setOf(0, 12)) { "zip -d ${jar.name} failed: $out" }
        }
        proguardOut.get().asFile.listFiles().orEmpty().forEach { jar ->
            when {
                jar.name.startsWith("sqlite-jdbc") -> strip(
                    jar,
                    listOf("FreeBSD", "Linux", "Mac", "Windows").filter { it != sqliteKeep }
                        .map { "org/sqlite/native/$it/*" },
                )
                jar.name.matches(Regex("jna(-jpms)?-[0-9].*\\.jar")) -> strip(
                    jar,
                    jnaOses.filter { it != jnaKeep }.map { "com/sun/jna/$it-*" },
                )
            }
        }
    }
}

kotlin.sourceSets.getByName("jvmMain").resources.srcDir(files(layout.buildDirectory.dir("engineJsResources")).builtBy(stageEngineJs))
kotlin.sourceSets.getByName("jvmMain").resources.srcDir(files(layout.buildDirectory.dir("webviewJsResources")).builtBy(stageWebViewJs))
