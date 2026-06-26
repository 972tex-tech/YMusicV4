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
}
