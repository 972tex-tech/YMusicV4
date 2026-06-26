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
            implementation(Libs.Kotlinx.serializationJson)
            implementation(Libs.Okio.core)
            implementation(project(":kmpHttp:core"))
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(Libs.Kotlinx.coroutinesTest)
        }
    }
}
