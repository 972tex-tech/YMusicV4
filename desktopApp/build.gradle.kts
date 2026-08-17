import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.file.Files
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val desktopVersion = "1.0.0"
// BUMP ON EVERY RELEASE. The update check compares this and nothing else — desktopVersion is a
// display string. Desktop counts separately from androidApp's versionCode (separate cadence).
val desktopVersionCode = 1
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
// Where installed copies look for releases, and the key that authenticates them. Both come from
// the environment/local.properties so a fork builds without ours, and an unsigned-manifest build
// is impossible: no key means no update checks at all.
val updateUrl: String = providers.gradleProperty("ymusic.updateManifestUrl").orNull
    ?: updateManifestUrl()
val updatePublicKeyValue: String = providers.gradleProperty("ymusic.updatePublicKey").orNull
    ?: updatePublicKey()

// The helper binaries the first run downloads (ffmpeg), signed with the SAME key as releases —
// they get executed, so an unauthenticated fetch is not an option. Empty leaves the first run
// asking the user to install ffmpeg instead of fetching it.
val toolsUrl: String = providers.gradleProperty("ymusic.toolsManifestUrl").orNull
    ?: toolsManifestUrl()

// Dev and test runs exec the prebuilt ffmpeg from the latest ffmpeg-build release instead of
// provisioning one, through the same -Dio.ymusic.ffmpeg override a developer would pass by hand.
// A SHIPPED build never sees this: it downloads ffmpeg on first run against the signed manifest
// above, which is the path that has to keep working and so is what packaging exercises.
// Set on the run and test TASKS, never on compose.desktop.application.jvmArgs — those are baked
// into the packaged launcher, and a build-machine path in a shipped app would point at nothing.
val ffmpegForDev: File? = FfmpegRelease.hostBinary(project)
if (ffmpegForDev != null) {
    val syncFfmpegHost = rootProject.tasks.named("syncFfmpegHost")
    val ffmpegPath = ffmpegForDev.absolutePath
    tasks.withType<Test>().configureEach {
        dependsOn(syncFfmpegHost)
        systemProperty("io.ymusic.ffmpeg", ffmpegPath)
    }
    // `run` and the probes, but NOT runDistributable/runRelease*: those exist to try the packaged
    // experience, whose first run is supposed to provision ffmpeg itself. Handing them one would
    // mask the very path they are there to exercise.
    tasks.withType<JavaExec>().matching { !it.name.contains("Distributable") && !it.name.contains("Release") }
        .configureEach {
            dependsOn(syncFfmpegHost)
            systemProperty("io.ymusic.ffmpeg", ffmpegPath)
        }
}

val generateBuildConfig by tasks.registering {
    val dsn = sentryDsn()
    val release = "ymusic-desktop@$desktopVersion+${gitCommit()}"
    val outDir = layout.buildDirectory.dir("generated/buildconfig/kotlin")
    inputs.property("dsn", dsn)
    inputs.property("release", release)
    inputs.property("engineJsVersion", Versions.engineJsBackendVersion)
    inputs.property("versionCode", desktopVersionCode)
    inputs.property("updateUrl", updateUrl)
    inputs.property("updatePublicKey", updatePublicKeyValue)
    inputs.property("toolsUrl", toolsUrl)
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

            // What the update check compares against a manifest's versionCode.
            internal const val APP_VERSION_CODE: Int = $desktopVersionCode
            internal const val APP_VERSION_NAME = "$desktopVersion"

            // The update manifest and the key that authenticates it. Empty disables update
            // checks entirely — with nothing to verify against, trusting the network is worse
            // than not checking.
            internal const val UPDATE_MANIFEST_URL = "$updateUrl"
            internal const val UPDATE_PUBLIC_KEY = "$updatePublicKeyValue"

            // Where the first run fetches ffmpeg from; verified against UPDATE_PUBLIC_KEY.
            internal const val TOOLS_MANIFEST_URL = "$toolsUrl"
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

// The injected WebView player script, prod build, staged as a jar resource. webview-js is not a
// Gradle module, so this drives its npm pipeline directly; it writes into our build dir so the
// dev dist/ stays untouched. Needs node + webview-js/node_modules on the build host.
val stageWebViewJs by tasks.registering(Exec::class) {
    val outFile = layout.buildDirectory.file("webviewJsResources/webview_ymusic.js")
    workingDir = rootProject.file("webview-js")
    inputs.dir(rootProject.file("webview-js/src"))
    inputs.files(rootProject.file("webview-js/build.mjs"), rootProject.file("webview-js/package.json"))
    outputs.file(outFile)
    commandLine("node", "build.mjs", "--prod", "--out", outFile.get().asFile.absolutePath)
}

// Our OSR browser paints AWT images, so JCEF's GL canvas path is never instantiated and
// jogl+gluegen are dead weight. Excluded at configuration level: jcefmaven arrives through two
// declarations (:player's and ours) and jcef-api re-declares jogl itself.
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
                implementation(compose.desktop.currentOs)
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

// Producer side of the update flow (client side: io.ymusic.desktop.update):
//   ./gradlew :desktopApp:packageReleaseDmg -Pymusic.debugLogs=false
//   ./gradlew :desktopApp:signUpdateManifest -Pymusic.updateBaseUrl=https://.../download/v1.0.0
// Each platform is built on its own machine; signUpdateManifest merges its entry into
// build/update/platforms.json so one manifest can describe all of them.
// These import java.security rather than qualifying it: inside a task action `java` resolves to
// the Java plugin extension, not the package root.
tasks.register("generateUpdateKey") {
    group = "distribution"
    description = "Print a fresh Ed25519 key pair for signing update manifests."
    doLast {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val enc = Base64.getEncoder()
        logger.lifecycle(
            "Add to local.properties (NEVER commit the private key):\n\n" +
                "update.public.key=" + enc.encodeToString(pair.public.encoded) + "\n" +
                "update.private.key=" + enc.encodeToString(pair.private.encoded),
        )
    }
}

tasks.register("signUpdateManifest") {
    group = "distribution"
    description = "Hash this platform's release artifact and write a signed update manifest."
    val outDir = layout.buildDirectory.dir("update")
    val binariesDir = layout.buildDirectory.dir("compose/binaries")
    val privateKey = updatePrivateKey()
    val versionCode = desktopVersionCode
    val versionName = desktopVersion
    val baseUrlProp = providers.gradleProperty("ymusic.updateBaseUrl").orNull
    val notesProp = providers.gradleProperty("ymusic.releaseNotes").orNull
    outputs.dir(outDir)
    doLast {
        check(privateKey.isNotBlank()) {
            "no signing key: set YMUSIC_UPDATE_PRIVATE_KEY or update.private.key in local.properties " +
                "(:desktopApp:generateUpdateKey mints one)"
        }
        val artifact = binariesDir.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension in setOf("dmg", "msi", "deb") }
            .maxByOrNull { it.lastModified() }
            ?: error("no release artifact under ${binariesDir.get().asFile} — run a packageRelease* task first")

        val os = org.gradle.internal.os.OperatingSystem.current()
        val arch = if (System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")) "arm64" else "x64"
        val platformKey = when {
            os.isMacOsX -> "mac-$arch"
            os.isWindows -> "windows-$arch"
            else -> "linux-$arch"
        }
        fun hash(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().buffered().use { ins ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        val sha256 = hash(artifact)
        val baseUrl = (baseUrlProp ?: "https://example.invalid/releases/v$versionName").trimEnd('/')

        // Entries from other machines' runs survive, so one manifest can cover every platform.
        val out = outDir.get().asFile.apply { mkdirs() }
        val store = File(out, "platforms.json")
        val entries = linkedMapOf<String, String>()
        if (store.isFile) {
            Regex(""""([a-z]+-[a-z0-9]+)":(\{[^}]*\})""").findAll(store.readText())
                .forEach { entries[it.groupValues[1]] = it.groupValues[2] }
        }
        // Every file of the installed app, addressed by content hash: an update fetches only the
        // hashes it does not already have, so an unchanged JVM or dependency costs nothing.
        val appImage = binariesDir.get().asFile.resolve("main-release/app").listFiles().orEmpty()
            .firstOrNull { it.isDirectory }
            ?: error("no app image under ${binariesDir.get().asFile}/main-release/app")
        val blobs = File(outDir.get().asFile, "blobs").apply { mkdirs() }
        val fileEntries = StringBuilder()
        var files = 0
        var reusedBlobs = 0
        appImage.walkTopDown().filter { it.isFile }.forEach { f ->
            val h = hash(f)
            val rel = f.relativeTo(appImage).path
            val blob = File(blobs, h)
            if (blob.exists()) {
                reusedBlobs++
            } else {
                // Hardlink when the filesystem allows it — a release is ~125MB and copying every
                // build is pure waste.
                runCatching { Files.createLink(blob.toPath(), f.toPath()) }
                    .onFailure { f.copyTo(blob, overwrite = true) }
            }
            if (files++ > 0) fileEntries.append(",")
            fileEntries.append("""{"path":"${rel.replace("\\", "/")}","sha256":"$h","size":"${f.length()}"}""")
        }

        entries[platformKey] = """{"installer":{"url":"$baseUrl/${artifact.name}","sha256":"$sha256",""" +
            """"size":"${artifact.length()}"},"blobBaseUrl":"$baseUrl/blobs",""" +
            """"root":"${appImage.name}","files":[$fileEntries]}"""
        val platformsJson = entries.entries.joinToString(",") { """"${it.key}":${it.value}""" }
        store.writeText(platformsJson)

        val notes = notesProp.orEmpty().replace("\\", "\\\\").replace("\"", "\\\"")
        val payload = """{"manifestVersion":2,"versionCode":$versionCode,"versionName":"$versionName",""" +
            """"notes":"$notes","platforms":{$platformsJson}}"""
        val key = KeyFactory.getInstance("Ed25519")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey)))
        val signature = Signature.getInstance("Ed25519").run {
            initSign(key)
            update(payload.toByteArray())
            Base64.getEncoder().encodeToString(sign())
        }
        val escaped = payload.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        File(out, "update.json").writeText("""{"payload":"$escaped","signature":"$signature"}""")
        logger.lifecycle("signed update.json: $platformKey, versionCode $versionCode, ${artifact.name}")
        logger.lifecycle("  $files files -> ${blobs.name}/ ($reusedBlobs already present from an earlier release)")
    }
}

// Local stand-in for the release/config service (see MockUpdateServer.kt):
//   ./gradlew :desktopApp:mockUpdateServer -PmockArgs="8899 build/mock-update 9.9.9"
tasks.register<JavaExec>("mockUpdateServer") {
    group = "verification"
    description = "Serve a signed update manifest + JS remote config from localhost."
    val jvmMainCompilation = kotlin.jvm().compilations.getByName("main")
    dependsOn(jvmMainCompilation.compileTaskProvider)
    classpath(jvmMainCompilation.output.allOutputs, jvmMainCompilation.runtimeDependencyFiles)
    mainClass.set("io.ymusic.desktop.update.MockUpdateServer")
    args((providers.gradleProperty("mockArgs").orNull ?: "").split(" ").filter { it.isNotBlank() })
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
        // Dev only: -Pymusic.setup.forceDownload makes the first run ignore an ffmpeg already on
        // this machine, so the download path can be exercised on a box that has one anyway.
        if (providers.gradleProperty("ymusic.setup.forceDownload").isPresent) {
            jvmArgs("-Dio.ymusic.setup.forceDownload=true")
        }
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
            macOS {
                // Stable across releases: the updater and Gatekeeper both identify the app by it.
                bundleID = "io.ymusic.desktop"
                // Signing is opt-in so a checkout without the identity still builds. An unsigned
                // build is fine to run locally and impossible to ship: Gatekeeper rejects it.
                val signingIdentity = macSigningIdentity()
                signing {
                    sign.set(signingIdentity.isNotBlank())
                    if (signingIdentity.isNotBlank()) identity.set(signingIdentity)
                }
                // The JVM and Chromium need executable memory a hardened runtime withholds; see
                // entitlements.plist for why each is there.
                entitlementsFile.set(project.file("entitlements.plist"))
                runtimeEntitlementsFile.set(project.file("entitlements.plist"))
                val appleId = macNotarizationAppleId()
                if (appleId.isNotBlank()) {
                    notarization {
                        appleID.set(appleId)
                        password.set(macNotarizationPassword())
                        teamID.set(macTeamId())
                    }
                }
            }
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

// sqlite-jdbc and JNA carry natives for every OS/arch; a package is per-OS, so the rest is dead
// weight. Stripped from the ProGuard output, which is what jpackage consumes. zip exit 12 =
// "nothing matched", which is fine.
tasks.matching { it.name == "proguardReleaseJars" }.configureEach {
    val proguardOut = layout.buildDirectory.dir("compose/tmp/main-release/proguard")
    // The plugin never clears this dir, so a jar whose name changes (a dependency version bump)
    // would leave its predecessor behind to be packaged alongside the new one.
    doFirst { proguardOut.get().asFile.deleteRecursively() }
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
        // ProGuard stamps the CURRENT TIME into every entry it writes, so a jar with identical
        // content hashes differently on each build. Content-addressed updates would then refetch
        // every unchanged library (measured: 36MB of a 124MB app). Normalize to a fixed instant.
        fun normalizeTimestamps(jar: File) {
            val entries = LinkedHashMap<String, ByteArray>()
            ZipFile(jar).use { zip ->
                for (e in zip.entries()) {
                    if (!e.isDirectory) entries[e.name] = zip.getInputStream(e).readBytes()
                }
            }
            val tmp = File(jar.absolutePath + ".norm")
            ZipOutputStream(tmp.outputStream().buffered()).use { out ->
                for ((name, bytes) in entries) {
                    // 1980-01-01, the earliest a zip entry can express.
                    out.putNextEntry(ZipEntry(name).apply { time = 315_532_800_000L })
                    out.write(bytes)
                    out.closeEntry()
                }
            }
            check(tmp.renameTo(jar)) { "could not replace ${jar.name} with its normalized copy" }
        }

        proguardOut.get().asFile.listFiles().orEmpty().forEach { jar ->
            when {
                jar.name.startsWith("sqlite-jdbc") -> strip(
                    jar,
                    listOf("FreeBSD", "Linux", "Mac", "Windows").filter { it != sqliteKeep }
                        .map { "org/sqlite/native/$it/*" },
                )
                // JetBrains ship BOTH macOS dylibs in each per-arch skiko runtime jar, so an
                // Intel package carries 21MB of arm64 native it can never load.
                jar.name.startsWith("skiko-awt-runtime") -> strip(
                    jar,
                    listOf("macos-arm64", "macos-x64", "linux-x64", "linux-arm64", "windows-x64")
                        .filter { !jar.name.contains(it) }
                        .flatMap { listOf("libskiko-$it.dylib", "libskiko-$it.dylib.sha256") },
                )
                jar.name.matches(Regex("jna(-jpms)?-[0-9].*\\.jar")) -> strip(
                    jar,
                    jnaOses.filter { it != jnaKeep }.map { "com/sun/jna/$it-*" },
                )
            }
        }
        proguardOut.get().asFile.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "jar" }
            .forEach(::normalizeTimestamps)
    }
}

kotlin.sourceSets.getByName("jvmMain").resources.srcDir(files(layout.buildDirectory.dir("engineJsResources")).builtBy(stageEngineJs))
kotlin.sourceSets.getByName("jvmMain").resources.srcDir(files(layout.buildDirectory.dir("webviewJsResources")).builtBy(stageWebViewJs))
