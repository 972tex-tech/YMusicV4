@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    // AGP KMP plugin (same as :media-store / :kmpFfmpeg) — supplies the `android {}` KMP target.
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    // LocalPlayerContext is expect/actual: each UI platform may carry its shared video container.
    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
            }
        }
    }
    sourceSets.all { languageSettings.optIn("kotlin.ExperimentalMultiplatform") }

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    android {
        namespace = "io.ymusic.player"
        compileSdk = Versions.compileSdk
        minSdk = Versions.minSdk
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // Apple (iOS device + simulator, macOS) share ONE AVPlayer backend (appleMain). Both macOS and
    // iOS export a `Player.framework` so the native SwiftUI player-lab apps (spikes/macos-player-lab,
    // spikes/ios-player-lab) can drive the real AVFoundation + WKWebView backends.
    withIosTargets {
        binaries.framework {
            baseName = "Player"
            isStatic = false
        }
    }
    withMacosTargets {
        binaries.framework {
            baseName = "Player"
            isStatic = false
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        /**
         * commonMain — the LocalPlayer interface + model + the expect factory. One responsibility:
         * play a SINGLE local file (load / play / pause / seek / stop). No queue, no gapless.
         * ├── appleMain   → AVPlayer          (iOS + macOS)   [reference]
         * ├── androidMain → Media3 ExoPlayer                  [reference]
         * └── jvmMain     → VLCJ (libVLC)                     [best effort; needs libVLC at runtime]
         */
        val commonMain by getting {
            dependencies {
                api(project(":kmpCommon"))
                api(Libs.Kotlinx.coroutinesCore)
                // JsonElement runtime only (no @Serializable/codegen) — the WebView YouTube engine's
                // ymusicAction command + event wire format.
                implementation(Libs.Kotlinx.serializationJson)
                // The WebView engine fetches the YouTube embed HTML to inject the bundle into.
                // `api` so KmpHttpClient (a public param of createWebViewPlayer) exports through
                // the Apple Player.framework.
                api(project(":kmpHttp:core"))
                // The player-cache router the WebView interception adapters serve from
                // (CefResourceHandler on desktop, shouldInterceptRequest on Android, the message
                // channel on Apple). `api`: LocalMediaProxy is a public param of WebViewPlayerContext,
                // so consumers (the app shells) must see the type to build one.
                api(project(":yt-media"))
            }
        }

        androidMain.dependencies {
            implementation(Libs.Media3.exoplayer)
            // api: Media3PlayerAdapter IS a media3 Player — consumers (the app's MediaSession) need the type.
            api(Libs.Media3.common)
            implementation(Libs.Media3.ui)
            implementation(Libs.Kotlinx.coroutinesAndroid)
        }

        jvmMain.dependencies {
            implementation(Libs.Vlcj.core)
            // JCEF (Chromium) for the desktop WebView YouTube host. The app owns CefApp
            // init/download; :player only drives a CefClient/CefBrowser it is handed.
            implementation(Libs.Jcef.maven)
            // Provides Dispatchers.Main on JVM desktop (Swing EDT) — the WebView engine marshals
            // the fetched HTML load back to the UI thread. Android/iOS supply Main natively.
            implementation(Libs.Kotlinx.coroutinesSwing)
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(Libs.Kotlinx.coroutinesTest)
            }
        }
    }
}
