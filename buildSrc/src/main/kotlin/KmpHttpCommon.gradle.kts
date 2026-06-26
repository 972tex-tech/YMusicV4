import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeHostTest

plugins {
    kotlin("multiplatform")
}

@OptIn(ExperimentalWasmDsl::class)
kotlin {
    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    sourceSets.all {
        languageSettings.optIn("kotlin.ExperimentalMultiplatform")
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    withAppleTargets()
    withLinuxTargets()

    listOf(js(), wasmJs()).forEach {
        it.browser {
            testTask {
                useKarma {
                    useConfigDirectory(project.projectDir.resolve("../karma.config.d"))
                    useChromeHeadless()
                }
            }
        }
        it.nodejs {
            testTask {
                useMocha {
                    timeout = "60s"
                }
            }
        }
    }

    applyDefaultHierarchyTemplate()
}

// Enable macOS host tests
tasks.withType<KotlinNativeHostTest>().configureEach {
    enabled = true
}
