plugins {
    // AGP + core Kotlin plugins - versions resolved from the buildSrc classpath (see
    // buildSrc/build.gradle.kts), so they're applied without a version here.
    id("com.android.application") apply false
    id("com.android.library") apply false
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
