plugins {
    id("com.android.application") version Versions.agp apply false
    id("com.android.library") version Versions.agp apply false
    // Core Kotlin plugins - version resolved from buildSrc classpath (see buildSrc/build.gradle.kts)
    kotlin("android") apply false
    kotlin("multiplatform") apply false
    // Kotlin compiler plugins - need explicit version (not bundled in kotlin-gradle-plugin)
    kotlin("plugin.compose") version Versions.kotlin apply false
    kotlin("plugin.serialization") version Versions.kotlin apply false
    kotlin("plugin.allopen") version Versions.kotlin apply false
    id("org.jetbrains.compose") version Versions.composeMultiplatform apply false
    id("org.jetbrains.kotlinx.benchmark") version Versions.kotlinxBenchmark apply false
}

tasks.register("cleanBuildDir", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

// Make lifecycle clean task depend on our custom clean
tasks.matching { it.name == "clean" }.configureEach {
    dependsOn("cleanBuildDir")
}
