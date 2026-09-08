@file:Suppress("UnstableApiUsage")

import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

val mseNativeSrc = projectDir.resolve("native")

fun optFlag(name: String, default: Boolean): Boolean =
    when (project.findProperty(name)?.toString()) {
        "true" -> true
        "false" -> false
        else -> default
    }
val production = project.findProperty("production") == "true"
val mseNativeDebug = optFlag("mseDebug", !production)
val mseDebugCflag = if (mseNativeDebug) "-DMSE_DEBUG=1" else "-DMSE_DEBUG=0"
val mseDebugCmake = if (mseNativeDebug) "ON" else "OFF"

fun mseSdkPath(sdk: String): String =
    ProcessBuilder("xcrun", "--sdk", sdk, "--show-sdk-path").start()
        .inputStream.bufferedReader().readText().trim()

fun mseClangArch(targetName: String): List<String> = when (targetName) {
    "macosX64" -> listOf("-arch", "x86_64")
    "macosArm64" -> listOf("-arch", "arm64")
    "iosX64" -> listOf(
        "-arch", "x86_64", "-isysroot", mseSdkPath("iphonesimulator"),
        "-mios-simulator-version-min=12.0",
    )
    "iosSimulatorArm64" -> listOf(
        "-arch", "arm64", "-isysroot", mseSdkPath("iphonesimulator"),
        "-mios-simulator-version-min=12.0",
    )
    "iosArm64" -> listOf(
        "-arch", "arm64", "-isysroot", mseSdkPath("iphoneos"),
        "-miphoneos-version-min=12.0",
    )
    else -> emptyList()
}

fun mseLibDir(targetName: String) =
    layout.buildDirectory.dir("mse-native/$targetName").get().asFile

fun ffmpegIosPrefix(targetName: String): File? {
    val slug = when (targetName) {
        "iosArm64" -> "ios-arm64"
        "iosSimulatorArm64" -> "ios-simulator-arm64"
        else -> null
    } ?: return null
    val dir = rootProject.projectDir.parentFile.resolve("ffmpeg-build/out/$slug")
    return dir.takeIf { File(it, "lib/libavcodec.a").isFile }
}

fun registerMseNativeLib(targetName: String): TaskProvider<Exec> {
    val taskName = "buildMseHost${targetName.replaceFirstChar { it.uppercase() }}"
    tasks.findByName(taskName)?.let {
        @Suppress("UNCHECKED_CAST")
        return tasks.named(taskName) as TaskProvider<Exec>
    }
    val out = mseLibDir(targetName)
    val ffmpeg = ffmpegIosPrefix(targetName)
    val qjsInc = rootProject.projectDir.resolve("quickjs/native")
    val task = tasks.register<Exec>(taskName) {
        doFirst { out.mkdirs() }
        inputs.dir(mseNativeSrc)
        if (ffmpeg != null) {
            inputs.dir(File(ffmpeg, "include"))
            inputs.dir(qjsInc)
        }
        outputs.file(File(out, "libmsehost.a"))
        workingDir(out)
        val arch = mseClangArch(targetName).joinToString(" ")
        val cmd = if (ffmpeg != null) {
            val srcs = listOf("mse_host.c", "mse_ffmpeg.c", "mse_vt.c")
                .joinToString(" ") { "'${mseNativeSrc.resolve(it)}'" }
            "set -e; cc $arch -O2 -w $mseDebugCflag -c $srcs " +
                "-I'${mseNativeSrc.absolutePath}' -I'${qjsInc.absolutePath}' " +
                "-I'${File(ffmpeg, "include").absolutePath}' && ar rcs libmsehost.a *.o"
        } else {
            "set -e; cc $arch -O2 -w $mseDebugCflag -c '${mseNativeSrc.resolve("mse_stub.c")}' " +
                "-I'${mseNativeSrc.absolutePath}' && ar rcs libmsehost.a *.o"
        }
        commandLine("bash", "-c", cmd)
    }
    return task
}

val configureMseNative: KotlinNativeTarget.() -> Unit = {
    val targetName = name
    val libTask = registerMseNativeLib(targetName)
    val libDir = mseLibDir(targetName)
    val ffmpeg = ffmpegIosPrefix(targetName)
    compilations.getByName("main").cinterops.create("mse") {
        defFile(project.file("native/cinterop/mse.def"))
        includeDirs(mseNativeSrc)
        extraOpts("-libraryPath", libDir.absolutePath)
        if (ffmpeg != null) {
            // Baked into the klib, so :dynamic-engine / :playground do not repeat -L/-l/frameworks.
            linkerOpts("-L${File(ffmpeg, "lib").absolutePath}")
            linkerOpts("-lavformat", "-lavcodec", "-lswscale", "-lswresample", "-lavutil")
            linkerOpts(
                "-framework", "AudioToolbox",
                "-framework", "CoreAudio",
                "-framework", "VideoToolbox",
                "-framework", "CoreMedia",
                "-framework", "CoreFoundation",
                "-framework", "CoreVideo",
                "-lz", "-lbz2", "-liconv",
            )
        }
    }
    tasks.named("cinteropMse${targetName.replaceFirstChar { it.uppercase() }}").configure {
        dependsOn(libTask)
        inputs.file(libDir.resolve("libmsehost.a"))
    }
}

kotlin {
    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
            }
        }
    }
    sourceSets.all { languageSettings.optIn("kotlin.ExperimentalMultiplatform") }

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    android {
        namespace = "io.ymusic.mse"
        compileSdk = Versions.compileSdk
        minSdk = Versions.minSdk
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        packaging { jniLibs { useLegacyPackaging = true } }
    }

    withIosTargets(configureMseNative)
    withMacosTargets(configureMseNative)

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":kmpCommon"))
                api(Libs.Kotlinx.coroutinesCore)
            }
        }

        jvmMain.dependencies {
            implementation(Libs.Kotlinx.coroutinesSwing)
        }
    }
}

// Host libav JNI (leftover + demux + clock). Android NDK build is the same C; the only JNI
// media call is setVideoSurface.
val mseJniDir = layout.buildDirectory.dir("mse-jni")
val javaHomeForJni: String = run {
    val candidates = buildList {
        System.getenv("JAVA_HOME")?.let { add(it) }
        add(System.getProperty("java.home"))
        runCatching {
            ProcessBuilder("/usr/libexec/java_home").start().inputStream.bufferedReader().readText().trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { add(it) }
    }
    candidates.firstOrNull { File(it, "include/jni.h").exists() } ?: ""
}
val mseCmakeConfigure by tasks.registering(Exec::class) {
    onlyIf { javaHomeForJni.isNotEmpty() && mseNativeSrc.resolve("CMakeLists.txt").exists() }
    val out = mseJniDir.get().asFile
    doFirst { out.mkdirs() }
    inputs.dir(mseNativeSrc)
    inputs.property("javaHome", javaHomeForJni)
    inputs.property("mseDebug", mseNativeDebug)
    outputs.file(File(out, "CMakeCache.txt"))
    environment("JAVA_HOME", javaHomeForJni)
    commandLine(
        "cmake", "-S", mseNativeSrc.absolutePath, "-B", out.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release", "-DMSE_DEBUG=$mseDebugCmake",
    )
}
val mseCmakeBuild by tasks.registering(Exec::class) {
    dependsOn(mseCmakeConfigure)
    onlyIf { javaHomeForJni.isNotEmpty() && mseNativeSrc.resolve("CMakeLists.txt").exists() }
    inputs.dir(mseNativeSrc)
    outputs.dir(mseJniDir)
    commandLine("cmake", "--build", mseJniDir.get().asFile.absolutePath, "-j", "4")
}
tasks.withType<Test>().configureEach {
    dependsOn(mseCmakeBuild)
    systemProperty("mse.native.dir", mseJniDir.get().asFile.absolutePath)
}
val mseHostJniFolder: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val osPrefix = when {
        os.contains("mac") || os.contains("osx") -> "macos"
        os.contains("linux") -> "linux"
        os.contains("win") -> "windows"
        else -> "unknown"
    }
    val archSuffix = when (arch) {
        "aarch64" -> "aarch64"
        "amd64", "x86_64" -> "x64"
        else -> arch
    }
    "${osPrefix}_$archSuffix"
}
val mseJniResourcesDir = layout.buildDirectory.dir("generated/mseJniResources")
val bundleMseJniLib by tasks.registering(Copy::class) {
    dependsOn(mseCmakeBuild)
    from(mseJniDir) { include(System.mapLibraryName("mseffmpegjni")) }
    into(mseJniResourcesDir.map { it.dir("jni/$mseHostJniFolder") })
}
kotlin.sourceSets.getByName("jvmMain").resources.srcDir(files(mseJniResourcesDir).builtBy(bundleMseJniLib))

// Android NDK: same C as the host, linked against ffmpeg-build out/android-<abi>.
val ffmpegBuildDir = (rootProject.projectDir.parentFile.resolve("ffmpeg-build")).takeIf { it.isDirectory }
val mseAndroidAbis = listOf("arm64-v8a", "x86_64")
val ndkDir: File? = run {
    val sdk = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: File(System.getProperty("user.home"), "Library/Android/sdk").takeIf { it.exists() }?.path
    sdk?.let { File(it, "ndk").listFiles()?.filter { d -> d.isDirectory }?.maxByOrNull { d -> d.name } }
}
val stageAndroidJni by tasks.registering {
    val jniLibsDir = projectDir.resolve("src/androidMain/jniLibs")
    inputs.dir(mseNativeSrc)
    outputs.dir(jniLibsDir)
    onlyIf {
        ffmpegBuildDir != null && mseAndroidAbis.any { File(ffmpegBuildDir, "out/android-$it/lib/libavformat.a").isFile }
    }
    doLast {
        val ndk = ndkDir ?: error("Android NDK not found (set ANDROID_HOME with an installed NDK)")
        val toolchain = File(ndk, "build/cmake/android.toolchain.cmake")
        val root = ffmpegBuildDir ?: error("ffmpeg-build not found next to YMusicV4-mse")
        fun sh(vararg args: String) {
            val p = ProcessBuilder(*args).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0) throw GradleException("command failed: ${args.joinToString(" ")}\n$out")
        }
        mseAndroidAbis.forEach { abi ->
            val prefix = File(root, "out/android-$abi")
            if (!File(prefix, "lib/libavformat.a").isFile) return@forEach
            val buildDir = layout.buildDirectory.dir("mse-android/$abi").get().asFile
            buildDir.mkdirs()
            sh(
                "cmake", "-S", mseNativeSrc.absolutePath, "-B", buildDir.absolutePath,
                "-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}",
                "-DANDROID_ABI=$abi", "-DANDROID_PLATFORM=android-${Versions.minSdk}",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DMSE_DEBUG=$mseDebugCmake",
                "-DFFMPEG_ROOT=${prefix.absolutePath}",
            )
            sh("cmake", "--build", buildDir.absolutePath, "-j", "4")
            val so = File(buildDir, "libmseffmpegjni.so")
            val dest = File(jniLibsDir, "$abi/libmseffmpegjni.so")
            dest.parentFile.mkdirs()
            so.copyTo(dest, overwrite = true)
            val hostTag = when {
                System.getProperty("os.name").lowercase().contains("mac") -> "darwin-x86_64"
                else -> "linux-x86_64"
            }
            val strip = File(ndk, "toolchains/llvm/prebuilt/$hostTag/bin/llvm-strip")
            if (strip.isFile) sh(strip.absolutePath, "--strip-unneeded", dest.absolutePath)
        }
    }
}
tasks.matching {
    it.name.matches(Regex("merge.*JniLibFolders"))
}.configureEach { dependsOn(stageAndroidJni) }
