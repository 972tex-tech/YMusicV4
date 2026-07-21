@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    // AGP KMP plugin (same as :media-store / :player) — supplies the `android {}` KMP target.
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    android {
        namespace = "ymusic.settings"
        compileSdk = Versions.compileSdk
        minSdk = Versions.minSdk
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    withIosTargets()
    withMacosTargets()
    withLinuxTargets()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(Libs.Kotlinx.coroutinesCore)
        }

        jvmMain.dependencies {
            // api: okio Path/FileSystem are FileSettingsStore.open parameters.
            api(Libs.Okio.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(Libs.Kotlinx.coroutinesTest)
        }

        jvmTest.dependencies {
            implementation(Libs.Okio.fakeFileSystem)
        }
    }
}
