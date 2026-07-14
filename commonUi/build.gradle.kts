import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
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
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation(Libs.Kotlinx.coroutinesCore)
            }
        }
    }
}
