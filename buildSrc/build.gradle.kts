plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Required for kmp-targets convention plugin.
    // Keep version in sync with Versions.kotlin in Dependencies.kt
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    // Android Gradle Plugin on the shared buildSrc classpath so modules can apply
    // `id("com.android.library")`/`application` without a version (and KMP's androidTarget can infer
    // the AGP version under configuration-on-demand). Keep in sync with Versions.agp.
    implementation("com.android.tools.build:gradle:8.7.3")
}
