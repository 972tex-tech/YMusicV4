import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    kmpHttpCommonConfig()

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    withAppleTargets {
        binaries.framework {
            baseName = "kmpHttpJson"
            isStatic = true
        }
    }

    withLinuxTargets()

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting
        val commonTest by getting

        commonMain.dependencies {
            implementation(Libs.Kotlinx.coroutinesCore)
            // api: JsonReader's public surface exposes JsonElement and BufferedAsyncSource
            api(Libs.Kotlinx.serializationJson)
            api(project(":kmpCommon"))
            implementation(Libs.Okio.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(Libs.Kotlinx.coroutinesTest)
        }
    }
}
