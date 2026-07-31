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
        namespace = "ymusic.ytmedia"
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
            // api: okio Source/Sink/Buffer are the module's byte API (UMP streams, proto payloads).
            api(Libs.Okio.core)
            // api: SabrSession's public constructor takes a KmpHttpClient (the app's standard client).
            api(project(":kmpHttp:core"))
            implementation(Libs.Kotlinx.coroutinesCore)
            // MediaBlobCache guards its in-memory span index with a SynchronizedObject so the (non-suspend)
            // PinnedSource.close() can unpin without runBlocking.
            implementation(Libs.Kotlinx.atomicfu)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(Libs.Kotlinx.coroutinesTest)
        }
    }
}
