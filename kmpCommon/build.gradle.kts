import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
}

// Compile-time build flags baked into generated source: a Kotlin `const val` inlines as a literal
// into every (inline) call site downstream, and the compiler eliminates dead `if (false)` branches
// — debug log sites are REMOVED from release output on every platform, no optimizer involved.
// Dev default: true. Release builds pass -Pymusic.debugLogs=false.
val debugLogs = providers.gradleProperty("ymusic.debugLogs").map(String::toBoolean).getOrElse(true)
val buildFlagsDir = layout.buildDirectory.dir("generated/buildFlags")
val generateBuildFlags by tasks.registering {
    inputs.property("debugLogs", debugLogs)
    outputs.dir(buildFlagsDir)
    doLast {
        buildFlagsDir.get().file("kmpcommon/log/BuildFlags.kt").asFile.apply { parentFile.mkdirs() }.writeText(
            """
            |package kmpcommon.log
            |
            |object BuildFlags {
            |    /** Baked at build time from -Pymusic.debugLogs (default true). */
            |    const val DEBUG_LOGS: Boolean = $debugLogs
            |}
            |""".trimMargin(),
        )
    }
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
        commonMain {
            kotlin.srcDir(generateBuildFlags)
            dependencies {
                api(Libs.Okio.core) // kmpcommon.io: AsyncSource/AsyncSink surface okio's Buffer
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(Libs.Kotlinx.coroutinesTest)
        }
    }
}
