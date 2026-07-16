plugins {
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ymusic.android"
    // okhttp-android 5.4 (via :kmpHttp) demands API 36+ for apps; libraries stay on Versions.compileSdk.
    compileSdk = 36

    defaultConfig {
        applicationId = "ymusic.android"
        minSdk = Versions.minSdk
        targetSdk = Versions.targetSdk
        versionCode = 1
        versionName = "4.0.0"
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
    }
}

/** The Android product shell over :commonUi (see MainActivity). Plain Android app module —
 *  AGP 9's built-in Kotlin (com.android.application cannot combine with the KMP plugin). */
dependencies {
    implementation(project(":commonUi"))
    implementation(Libs.AndroidX.coreKtx)
    implementation(Libs.AndroidX.activityCompose)
    implementation(Libs.Kotlinx.coroutinesAndroid)
    implementation(project(":kmpHttp:core"))
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
// (the Android resource reader resolves "composeResources/<package>/…" from assets). Drop when the
// compose plugin learns the new AGP KMP plugin.
val copyComposeResources by tasks.registering(Copy::class) {
    from(rootProject.file("commonUi/src/commonMain/composeResources"))
    into(layout.projectDirectory.dir("src/main/assets/composeResources/ymusic.ui.resources"))
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(copyWebViewJs, copyComposeResources)
}
