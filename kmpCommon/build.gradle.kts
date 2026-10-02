import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
}

// Public so the inline [Logger.debug] can see it. Feature gates use each module's own
// DEBUG_BUILD (same -Pymusic.debugLogs bake).
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
                implementation(Libs.Kotlinx.coroutinesCore) // LoggingWorker channel + IO writer
                implementation(Libs.Kotlinx.dateTime) // log line local HH.mm.ss.zzz
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(Libs.Kotlinx.coroutinesTest)
        }
    }
}
