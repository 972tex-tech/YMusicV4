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
        /** The desktop (JVM) product shell: builds the AppGraph (JCEF, engines, player) and boots
         *  the shared :commonUi App. `./gradlew :desktopApp:run`. */
        val jvmMain by getting {
            dependencies {
                implementation(project(":commonUi"))
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(Libs.Jcef.maven)
                implementation(project(":kmpHttp:core"))
                implementation(Libs.Kotlinx.coroutinesCore)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "ymusic.desktop.MainKt"
        // JCEF (the WebView YouTube host) needs AWT internals opened on the JBR/JDK.
        jvmArgs(
            "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "YMusic"
            packageVersion = "1.0.0"
        }
    }
}
