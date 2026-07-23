import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

compose.resources {
    packageOfResClass = "ymusic.ui.resources"
    // The shells reuse the brand assets (desktop window icon).
    publicResClass = true
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    android {
        namespace = "ymusic.ui"
        compileSdk = Versions.compileSdk
        minSdk = Versions.minSdk
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // Shared Compose UI for iOS — arm64 device + arm64 simulator only (Compose MP has no iosX64).
    // On this Intel host this compiles only under -PkmpTargets=all (cross-compiled for a device).
    // Exports one framework the iOS app embeds; MainViewController() is its Swift entry point.
    withComposeIosTargets {
        binaries.framework {
            baseName = "YMusicApp"
            isStatic = false
            // Compose MP 1.11.1's ui-uikit links iOS 26 symbols (UIViewLayoutRegion), which need the
            // iOS 26 SDK / Xcode 26. To build on an older Xcode, allow those undefined at link — Compose
            // @available-guards their use, so they're never touched below iOS 26. Drop when on Xcode 26.
            if (providers.gradleProperty("iosAllowUndefined").orNull == "true") {
                linkerOpts("-undefined", "dynamic_lookup")
            }
        }
    }

    sourceSets {
        /**
         * The shared Compose Multiplatform app UI (design/: drawer + Discover tabs + search +
         * now-playing + persistent mini-player). Pure common — the platform shells (:desktopApp,
         * :androidApp) build the AppGraph and inject the platform PlayerSurface composable; nothing
         * platform-specific lives here. iOS reuses this verbatim when its shell lands.
         */
        val commonMain by getting {
            dependencies {
                api(project(":player"))
                api(project(":dynamic-engine"))
                api(project(":media-store"))
                api(project(":image-loader"))
                api(project(":settings"))
                api(project(":kmpFfmpeg"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(Libs.Kotlinx.coroutinesCore)
            }
        }

        // The system back bridge (PlatformBackHandler) — the only Android-specific dependency.
        val androidMain by getting {
            dependencies {
                implementation(Libs.AndroidX.activityCompose)
            }
        }

        /**
         * Headless Compose UI tests (no window, no input automation): screens mount over a real
         * DynamicEngineManager driven by a canned HTTP client, so a test exercises Kotlin UI →
         * engine JS → parser → UI end to end on this desktop JVM.
         */
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(Libs.Kotlinx.coroutinesTest)
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
                implementation(compose.desktop.currentOs)
            }
        }
    }
}
