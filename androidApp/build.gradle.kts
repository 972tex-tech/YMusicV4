plugins {
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.ymusic.android"
    // okhttp-android 5.4 (via :kmpHttp) demands API 36+ for apps; libraries stay on Versions.compileSdk.
    compileSdk = 36

    defaultConfig {
        applicationId = "io.ymusic.android"
        minSdk = Versions.minSdk
        targetSdk = Versions.targetSdk
        versionCode = 1
        versionName = "4.0.0"
        buildConfigField("String", "SENTRY_DSN", "\"${sentryDsn()}\"")
        buildConfigField("String", "GIT_COMMIT", "\"${gitCommit()}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // The prebuilt ffmpeg ships as lib/<abi>/libffmpeg.so; legacy packaging extracts it to
        // nativeLibraryDir as a real on-disk file the media scanner can exec() (mmap'd libs can't be run).
        jniLibs { useLegacyPackaging = true }
    }

    // Stage the prebuilt ffmpeg into a jniLibs source dir (build output, gitignored) so it packs as a
    // per-ABI native lib. The metadata scanner execs it from nativeLibraryDir; see MainActivity.scanUris.
    // stageFfmpeg (below) fills the dir; the merge task depends on it explicitly (the source-set API
    // can't carry a Provider's task dependency, so a plain File + explicit dependsOn is required).
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("ffmpegJniLibs").get().asFile)
}

val stageFfmpeg by tasks.registering(Copy::class) {
    into(layout.buildDirectory.dir("ffmpegJniLibs").get().asFile)
    listOf("arm64-v8a", "x86", "x86_64").forEach { abi ->
        from(rootProject.file("ffmpeg-prebuilt-ymusic/android/$abi/libffmpeg.so")) { into(abi) }
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
    implementation(project(":yt-media")) // the player-cache router (LocalMediaProxy) for the WebView
    implementation(Libs.Media3.session)
    implementation(project(":telemetry")) // api: sentry-kotlin-multiplatform + SentryLogger
    // androidx.media.app.NotificationCompat.MediaStyle for the playback notification.
    implementation("androidx.media:media:1.7.0")
    implementation(compose.material3)
    implementation(compose.foundation)
}

// The injected YouTube player script rides as an asset — copied from the built webview-js bundle
// (npm run build there) rather than checked in. Absent bundle = absent asset: YouTube items then
// surface E60050 at runtime.
val copyWebViewJs by tasks.registering(Copy::class) {
    from(rootProject.file("webview-js/dist/webview_ymusic.js"))
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
    dependsOn(copyWebViewJs, copyComposeResources)
}
