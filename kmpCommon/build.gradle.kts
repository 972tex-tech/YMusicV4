import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
}

kotlin {
    sourceSets.all {
        languageSettings.optIn("kotlin.ExperimentalMultiplatform")
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Full native matrix (host-gated, via the shared KmpTargets helpers) so any consumer can depend on
    // this for every one of its targets: :kmpHttp:core / :kmpHttp:curl (apple + linux) and :kmpFfmpeg
    // (which additionally has tvOS/watchOS). No android target: kmpCommon is pure Kotlin, and an Android
    // KMP consumer resolves the `jvm` variant via Gradle's jvm-environment compatibility rule.
    withAppleTargets {
        binaries.framework {
            baseName = "kmpCommon"
            isStatic = true
        }
    }
    withLinuxTargets()

    sourceSets {
        commonMain.dependencies {
            api(Libs.Okio.core) // kmpcommon.io: AsyncSource/AsyncSink surface okio's Buffer
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(Libs.Kotlinx.coroutinesTest)
        }
    }
}
