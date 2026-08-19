@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    android {
        namespace = "io.ymusic.telemetry"
        compileSdk = Versions.compileSdk
        minSdk = Versions.minSdk
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // No Apple targets yet: sentry-kotlin-multiplatform needs the Sentry cocoa framework linked
    // into the Xcode shell — the iOS shell keeps its plain print logger until that lands.

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":kmpCommon"))
            api(Libs.Sentry.kmp)
            implementation(Libs.Kotlinx.coroutinesCore) // bindCrashReporting collects the consent flow
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
