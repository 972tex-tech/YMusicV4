import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        /**
         * A Compose Multiplatform playground for :player. The PlayerScreen lives in commonMain so
         * Android/iOS UI targets can reuse it verbatim; the Desktop (JVM) app is the runnable check.
         */
        val commonMain by getting {
            dependencies {
                implementation(project(":player"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(Libs.Kotlinx.coroutinesCore)
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "ymusic.playground.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "YMusicPlayground"
            packageVersion = "1.0.0"
        }
    }
}
