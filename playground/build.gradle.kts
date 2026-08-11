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
                // WebView YouTube check: JCEF (CefApp bootstrap) + an HTTP client for the engine.
                implementation(Libs.Jcef.maven)
                implementation(project(":kmpHttp:core"))
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.ymusic.playground.MainKt"
        // JCEF (the WebView YouTube host) needs AWT internals opened on the JBR/JDK.
        jvmArgs(
            "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "YMusicPlayground"
            packageVersion = "1.0.0"
        }
    }
}
