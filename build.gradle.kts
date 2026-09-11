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
    id("com.diffplug.spotless") version "8.8.0"
}

subprojects {
    installDebugBuildFlag()
}

spotless {
    kotlin {
        target("**/src/**/*.kt")
        // vendored / generated / foreign trees stay untouched
        targetExclude("**/build/**", "YMusicLegacy/**", "temp/**", "**/publicsuffix/PslDafsa.kt")
        // Spotless does not discover .editorconfig on its own — without the explicit path ktlint
        // runs on ktlint_official defaults and restructures code the config deliberately protects.
        ktlint("1.5.0").setEditorConfigPath("$rootDir/.editorconfig")
    }
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts", "buildSrc/*.gradle.kts")
        ktlint("1.5.0").setEditorConfigPath("$rootDir/.editorconfig")
    }
}

// ---- prebuilt ffmpeg (see buildSrc/FfmpegRelease.kt) --------------------------------------------
// Registered here rather than per module so the two staging directories have exactly one producer
// each. Consumers depend on the one they need: :androidApp and :kmpFfmpeg's device tests on the
// android ABI packs, :desktopApp's run and tests on the host pack (CLI + the same libav DSOs).
fun Project.registerFfmpegSync(name: String, wanted: Set<String>, dir: File, what: String) =
    tasks.register<SyncFfmpeg>(name) {
        group = "build setup"
        description = "Fetch $what from the latest ${FfmpegRelease.REPO} release."
        repo.set(FfmpegRelease.REPO)
        assets.set(wanted)
        FfmpegRelease.localDir(project)?.let { localDir.set(it) }
        offline.set(gradle.startParameter.isOffline)
        // --rerun-tasks counts too: it already re-runs this task, and a build asking for everything
        // to be redone from scratch should not be the one build that keeps a stale ffmpeg.
        force.set(
            providers.gradleProperty("ymusic.ffmpegUpdate").isPresent || gradle.startParameter.isRerunTasks,
        )
        outputDir.set(dir)
    }

registerFfmpegSync(
    "syncFfmpegHost",
    setOfNotNull(FfmpegRelease.hostAsset()),
    FfmpegRelease.hostDir(project),
    "the host ffmpeg pack (CLI + libav) the desktop app ships and the tests exec",
)

registerFfmpegSync(
    "syncFfmpegAndroid",
    FfmpegRelease.ANDROID_ABIS.map(FfmpegRelease::androidAsset).toSet(),
    FfmpegRelease.androidDir(project),
    "the per-ABI ffmpeg pack the apk ships as lib/<abi>/{libffmpeg.so,libav*.so}",
)

tasks.register("syncFfmpeg") {
    group = "build setup"
    description = "Fetch every prebuilt ffmpeg this host can use."
    dependsOn("syncFfmpegHost", "syncFfmpegAndroid")
}

tasks.register("cleanBuildDir", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

// Make lifecycle clean task depend on our custom clean
tasks.matching { it.name == "clean" }.configureEach {
    dependsOn("cleanBuildDir")
}

// ONE command refreshes everything derived from the engine-js bundle after a source edit or a
// backendVersion bump: build the bundle, restage the Android asset + desktop jar resource, and
// regenerate the embedded const + ENGINE_JS_EMBED_VERSION. (Consumers also pull all of this on
// their own builds — this exists so `./gradlew syncEngineJs` refreshes every copy in one go.)
tasks.register("syncEngineJs") {
    dependsOn(
        ":engine-js:closureJs", // build/dist/engine.js (typecheck + esbuild + Closure)
        ":androidApp:copyEngineJs", // → androidApp/src/main/assets/engine_ymusic.js
        ":desktopApp:stageEngineJs", // → desktopApp jar resources
        ":dynamic-engine:generateEngineJsBundleKt", // → embedded ENGINE_JS + its version const
    )
}
