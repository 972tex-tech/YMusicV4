plugins {
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.sentry.android.gradle") version "6.18.0"
}

// Upload-only Sentry integration: the plugin injects the proguard UUID and ships release
// mapping.txt + the Kotlin sources to Sentry, so events symbolicate with source context.
// Runtime stays out of the plugin's hands — the SDK arrives via :telemetry
// (sentry-kotlin-multiplatform), not auto-install, and bytecode instrumentation has no
// consumer here (no performance tracing).
sentry {
    org.set("na-6d")
    projectName.set("ymusic-kmp")
    authToken.set(sentryAuthToken())
    includeProguardMapping.set(true)
    autoUploadProguardMapping.set(sentryAuthToken().isNotEmpty())
    autoInstallation { enabled.set(false) }
    tracingInstrumentation { enabled.set(false) }
    includeSourceContext.set(sentryAuthToken().isNotEmpty())
    // The plugin only collects THIS module's sources; the app's frames live in the library
    // modules. Bundle every subproject source set that compiles into the Android app —
    // other targets' sets (jvmMain/desktop/apple) stay out, their actuals would collide.
    additionalSourceDirsForSourceContext.set(
        rootProject.subprojects
            .flatMap { p -> listOf("commonMain", "androidMain", "jvmCommonMain").map { p.file("src/$it/kotlin") } }
            .filter { it.isDirectory }
            .map { it.absolutePath }
            .toSet(),
    )
    telemetry.set(false)
}

val appVersionName = appVersionName()

// Outputs read YMusic-<versionName>-<abi|universal>-release.apk.
base { archivesName.set("YMusic-$appVersionName") }

// The ABIs every native the app needs — quickjs, mse, ffmpeg — is built for. A device on any other
// ABI could install an APK carrying only some of them and crash at the first engine call.
val appAbis = listOf("arm64-v8a", "x86_64")

android {
    namespace = "io.ymusic.android"
    // okhttp-android 5.4 (via :kmpHttp) demands API 36+ for apps; libraries stay on Versions.compileSdk.
    compileSdk = 36

    defaultConfig {
        applicationId = "io.ymusic.android"
        minSdk = Versions.minSdk
        targetSdk = Versions.targetSdk
        versionCode = 1
        versionName = appVersionName
        buildConfigField("String", "SENTRY_DSN", "\"${sentryDsn()}\"")
        buildConfigField("String", "GIT_COMMIT", "\"${gitCommit()}\"")
        // Base url of the remote config service. Empty = no request, compiled-in defaults.
        val configUrl = remoteConfigUrl()
        buildConfigField("String", "REMOTE_CONFIG_URL", "\"$configUrl\"")
        // local.server.ip with -PuseLocalServerIp ("" otherwise). A dev server is
        // plain http: cleartext is allowed only for a build whose service url is http.
        buildConfigField("String", "LOCAL_IP", "\"${localServerIp()}\"")
        manifestPlaceholders["usesCleartextTraffic"] = configUrl.startsWith("http://").toString()
        // The key remote engine/player bundles must be signed with. Empty = shipped bundles only.
        buildConfigField("String", "BUNDLE_PUBLIC_KEY", "\"${bundleSigningPublicKey()}\"")
        // The shipped engine_ymusic.js asset's revision (Versions.engineJsBackendVersion).
        buildConfigField("long", "ENGINE_JS_VERSION", "${Versions.engineJsBackendVersion}L")
    }

    buildFeatures {
        buildConfig = true
    }

    // One APK per ABI plus a universal one carrying both. All share the versionCode: they are the
    // same release, and the updater may move a device from the universal APK to its own split.
    splits {
        abi {
            isEnable = true
            reset()
            include(*appAbis.toTypedArray())
            isUniversalApk = true
        }
    }

    val releaseSigning = androidReleaseSigning()?.let { signing ->
        signingConfigs.create("release") {
            storeFile = signing.storeFile
            storePassword = signing.storePassword
            keyAlias = signing.keyAlias
            keyPassword = signing.keyPassword
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without android.signing.* the release build is debug-signed: installable for
            // on-device testing, never distributable.
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // libffmpeg.so (CLI) + :mse's libav*.so sit together under lib/<abi>/. Legacy packaging
        // extracts them to nativeLibraryDir so the scanner can exec() the CLI (mmap'd libs can't)
        // and both the CLI and MSE load the same libav via $ORIGIN.
        jniLibs {
            useLegacyPackaging = true
            // quickjs ships armeabi-v7a and ffmpeg x86; without the rest of the natives they are dead weight.
            excludes += listOf("armeabi-v7a", "x86").map { "lib/$it/**" }
        }
    }

    // Stage the prebuilt ffmpeg pack into a jniLibs source dir (build output, gitignored) so it packs
    // as per-ABI native libs. The metadata scanner execs libffmpeg.so from nativeLibraryDir; MSE
    // loads the same libav*.so. stageFfmpeg fills the dir; the merge task depends on it explicitly.
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("ffmpegJniLibs").get().asFile)
}

val stageFfmpeg by tasks.registering(Copy::class) {
    dependsOn(rootProject.tasks.named("syncFfmpegAndroid"))
    into(layout.buildDirectory.dir("ffmpegJniLibs").get().asFile)
    appAbis.forEach { abi ->
        from(FfmpegRelease.androidPackDir(project, abi)) {
            into(abi)
            exclude(".tars", ".tars/**", "*.tar.gz", "*.part")
            // Shared libav lives in the :mse AAR (same DSOs the CLI loads via $ORIGIN).
            exclude("libav*", "libsw*")
        }
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(stageFfmpeg)
}

/** The Android product shell over :commonUi (see MainActivity). Plain Android app module —
 *  AGP 9's built-in Kotlin (com.android.application cannot combine with the KMP plugin). */
dependencies {
    implementation(project(":commonUi"))
    implementation(project(":media-scanner")) // pulls :media-store + :kmpFfmpeg (api) for the local Library
    implementation(Libs.AndroidX.coreKtx)
    implementation(Libs.AndroidX.activityCompose)
    implementation(Libs.Kotlinx.coroutinesAndroid)
    implementation(project(":kmpHttp:core"))
    implementation(project(":projectCommon")) // LoggingWorker/FileLogger
    implementation(project(":remote-config"))
    implementation(project(":yt-media")) // the player-cache router (LocalMediaProxy) for the WebView
    implementation(Libs.Media3.session)
    implementation(project(":telemetry")) // api: sentry-kotlin-multiplatform + SentryLogger
    // androidx.media.app.NotificationCompat.MediaStyle for the playback notification.
    implementation("androidx.media:media:1.7.0")
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation(compose.components.resources)
    implementation(Libs.AndroidX.biometric)
    implementation(Libs.AndroidX.palette) // widget colours from the cover
    implementation(Libs.AndroidX.fragment)
    implementation(Libs.AndroidX.cameraCore)
    implementation(Libs.AndroidX.cameraCamera2)
    implementation(Libs.AndroidX.cameraLifecycle)
    implementation(Libs.AndroidX.cameraView)
    implementation(Libs.Zxing.core)
}

// The injected YouTube player script rides as an asset — copied from the built webview-js bundle
// (npm run build there) rather than checked in. Absent bundle = absent asset: YouTube items then
// surface E60050 at runtime.
val copyWebViewJs by tasks.registering(Copy::class) {
    val src = rootProject.file("webview-js/dist/webview_ymusic.js")
    from(src)
    into(layout.projectDirectory.dir("src/main/assets"))
    doFirst {
        check(src.isFile) {
            "webview_ymusic.js missing — run (cd webview-js && npm install && npm run build)"
        }
    }
}

// The engine bundle ships as an ASSET, not a compiled-in constant (its version is the
// generated ENGINE_JS_EMBED_VERSION const) — the
// JsSourceProvider reads it lazily and a cached remote update supersedes it at boot.
val engineJsBundle = configurations.create("engineJsBundle") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    "engineJsBundle"(project(":engine-js", "engineJs"))
}
val copyEngineJs by tasks.registering(Copy::class) {
    from(engineJsBundle) { rename { "engine_ymusic.js" } }
    into(layout.projectDirectory.dir("src/main/assets"))
}

// :commonUi's compose resources: the compose plugin doesn't package them into an Android artifact
// under AGP 9's com.android.kotlin.multiplatform.library, so the app ships them as assets itself
// (the Android resource reader resolves "composeResources/<package>/…" from assets). Must copy the
// PREPARED output, not the source dir — the runtime reads the converted strings.commonMain.cvr,
// never raw strings.xml. Drop when the compose plugin learns the new AGP KMP plugin.
val copyComposeResources by tasks.registering(Copy::class) {
    dependsOn(":commonUi:prepareComposeResourcesTaskForCommonMain")
    from(rootProject.file("commonUi/build/generated/compose/resourceGenerator/preparedResources/commonMain/composeResources"))
    into(layout.projectDirectory.dir("src/main/assets/composeResources/io.ymusic.ui.resources"))
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(copyWebViewJs, copyComposeResources, copyEngineJs)
}

// Release builds also read src/main/assets from lintVital's model writer — same staged inputs.
tasks.matching { it.name.contains("lint", ignoreCase = true) }.configureEach {
    dependsOn(copyWebViewJs, copyComposeResources, copyEngineJs)
}
