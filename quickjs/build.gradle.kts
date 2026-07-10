@file:Suppress("UnstableApiUsage")

import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

// ---------------------------------------------------------------------------------------------
// Native (Kotlin/Native) QuickJS static lib: compiled per Konan target and linked into the klib.
// ---------------------------------------------------------------------------------------------
val nativeSrc = projectDir.resolve("native")
val nativeLibTasks = mutableListOf<TaskProvider<Exec>>()

fun sdkPath(sdk: String): String =
    ProcessBuilder("xcrun", "--sdk", sdk, "--show-sdk-path").start()
        .inputStream.bufferedReader().readText().trim()

// clang arch + sysroot per Konan target. Linux compiles with the host clang on a Linux CI box.
fun clangArch(targetName: String): List<String> = when (targetName) {
    "macosX64" -> listOf("-arch", "x86_64")
    "macosArm64" -> listOf("-arch", "arm64")
    "iosX64" -> listOf("-arch", "x86_64", "-isysroot", sdkPath("iphonesimulator"), "-mios-simulator-version-min=12.0")
    "iosSimulatorArm64" -> listOf("-arch", "arm64", "-isysroot", sdkPath("iphonesimulator"), "-mios-simulator-version-min=12.0")
    "iosArm64" -> listOf("-arch", "arm64", "-isysroot", sdkPath("iphoneos"), "-miphoneos-version-min=12.0")
    else -> emptyList() // linuxX64: host clang on a Linux builder
}

fun nativeLibDir(targetName: String) =
    layout.buildDirectory.dir("quickjs-native/$targetName").get().asFile

fun registerNativeLib(targetName: String): TaskProvider<Exec> {
    val taskName = "buildQjsCore${targetName.replaceFirstChar { it.uppercase() }}"
    tasks.findByName(taskName)?.let {
        @Suppress("UNCHECKED_CAST")
        return tasks.named(taskName) as TaskProvider<Exec>
    }
    val out = nativeLibDir(targetName)
    val task = tasks.register<Exec>(taskName) {
        doFirst { out.mkdirs() }
        inputs.dir(nativeSrc)
        outputs.file(File(out, "libqjscore.a"))
        workingDir(out)
        val srcs = listOf(
            "quickjs/quickjs.c", "quickjs/cutils.c", "quickjs/libregexp.c",
            "quickjs/libunicode.c", "quickjs/dtoa.c", "quickjs_native.c", "quickjs_json.c",
            "quickjs_pipe.c",
        ).joinToString(" ") { "'${nativeSrc.resolve(it)}'" }
        // `cc` = clang on macOS (accepts the -arch flags), gcc on Linux (no -arch; native x86_64).
        val arch = clangArch(targetName).joinToString(" ")
        val cmd = "set -e; cc $arch -O2 -w -DCONFIG_VERSION='\"2026-06-04\"' -D_GNU_SOURCE " +
            "-I'${nativeSrc.resolve("quickjs")}' -I'$nativeSrc' -c $srcs && ar rcs libqjscore.a *.o"
        commandLine("bash", "-c", cmd)
    }
    nativeLibTasks.add(task)
    return task
}

kotlin {
    // expect/actual classes are Beta; opt in (same as the kmpHttp modules).
    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
            }
        }
    }
    // Applied uniformly to every source set (required for opt-in consistency across common/actuals).
    sourceSets.all {
        languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    android {
        namespace = "ymusic.quickjs"
        compileSdk = Versions.compileSdk
        minSdk = Versions.minSdk
        // Extract the .so to disk so System.loadLibrary always finds a real file.
        packaging { jniLibs { useLegacyPackaging = true } }
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }

        // Instrumented (on-emulator) tests: run the shared commonTest suite on a real Android runtime,
        // loading the NDK-built libquickjsjni.so. Auto-provisioned aosp emulator (API 30). Run with:
        //   ./gradlew :quickjs:pixelApi30AndroidDeviceTest
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            managedDevices {
                allDevices {
                    create<com.android.build.api.dsl.ManagedVirtualDevice>("pixelApi30") {
                        device = "Pixel 2"
                        apiLevel = 30
                        systemImageSource = "aosp"
                    }
                }
            }
        }
    }

    // Same QuickJS cinterop for every native target (host-gated). The per-target static lib is
    // embedded INTO the cinterop klib (konan `included/…`), so it propagates to consumers
    // automatically — a downstream module links it with no linkerOpts of its own. (This is how
    // quickjs-kt ships: its klib carries `default/targets/<t>/included/libquickjs_<t>.a`.)
    val configureQuickJs: org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.() -> Unit = {
        val targetName = name
        val libTask = registerNativeLib(targetName)
        val libDir = nativeLibDir(targetName)
        compilations.getByName("main").cinterops.create("qjs") {
            defFile(project.file("native/cinterop/qjs.def"))
            includeDirs(project.file("native"))
            // qjs.def names libqjscore.a in `staticLibraries`; give konan the per-target dir to find
            // it in, so it gets embedded in the klib's `included/` (portable, not a path reference).
            extraOpts("-libraryPath", libDir.absolutePath)
        }
        // cinterop reads libqjscore.a to embed it: build it first, and re-embed when it changes.
        tasks.named("cinteropQjs${targetName.replaceFirstChar { it.uppercase() }}").configure {
            dependsOn(libTask)
            inputs.file(libDir.resolve("libqjscore.a"))
        }
    }
    withMacosTargets(configureQuickJs)
    withIosTargets(configureQuickJs)
    withLinuxTargets(configureQuickJs)

    applyDefaultHierarchyTemplate()

    sourceSets {
        // Shared JVM+Android (JNI) code lives here; jvm/android only supply the library loader.
        val jvmCommonMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jvmCommonMain)
        androidMain.get().dependsOn(jvmCommonMain)

        commonMain.dependencies {
            api(project(":kmpCommon")) // Pipe.asAsyncSource()/asAsyncSink() expose kmpcommon.io types
            implementation(Libs.Kotlinx.coroutinesCore)
            implementation(Libs.Kotlinx.atomicfu) // SynchronizedObject for the onAccept registry
            implementation(Libs.Okio.core) // inbound pipe buffer (segment pool via Buffer/UnsafeCursor)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(Libs.Kotlinx.coroutinesTest)
        }

        // Instrumented tests run the shared commonTest suite on-device (the KMP android plugin has no
        // way to dependsOn commonTest across trees, so share by physical source dir).
        val androidDeviceTest by getting {
            kotlin.srcDir("src/commonTest/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation(Libs.Kotlinx.coroutinesTest)
                implementation(Libs.Testing.junitExt)
                implementation(Libs.Testing.runner)
            }
        }
    }
}

// (The static lib now rides inside the cinterop klib — see configureQuickJs — so the final link
// picks it up automatically, and cinterop's inputs.file tracks it for rebuilds. No per-link wiring.)

// ---------------------------------------------------------------------------------------------
// JVM JNI library (CMake): builds libquickjsjni for the host, bundled into the jar for consumers.
// ---------------------------------------------------------------------------------------------
val quickJsJniDir = layout.buildDirectory.dir("quickjs-jni")
val javaHomeForJni: String = run {
    val candidates = buildList {
        System.getenv("JAVA_HOME")?.let { add(it) }
        add(System.getProperty("java.home"))
        runCatching {
            ProcessBuilder("/usr/libexec/java_home").start().inputStream.bufferedReader().readText().trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { add(it) }
    }
    candidates.firstOrNull { File(it, "include/jni.h").exists() }
        ?: error("No JDK with JNI headers (include/jni.h) found. Checked: $candidates")
}

val cmakeConfigure by tasks.registering(Exec::class) {
    val out = quickJsJniDir.get().asFile
    doFirst { out.mkdirs() }
    inputs.dir(nativeSrc)
    inputs.property("javaHome", javaHomeForJni)
    outputs.file(File(out, "CMakeCache.txt"))
    environment("JAVA_HOME", javaHomeForJni)
    commandLine("cmake", "-S", nativeSrc.absolutePath, "-B", out.absolutePath, "-DCMAKE_BUILD_TYPE=Release")
}

val cmakeBuild by tasks.registering(Exec::class) {
    dependsOn(cmakeConfigure)
    inputs.dir(nativeSrc)
    outputs.dir(quickJsJniDir)
    commandLine("cmake", "--build", quickJsJniDir.get().asFile.absolutePath, "-j", "4")
}

// Our own tests load straight from the CMake output dir (fast, no extraction). Consumers instead
// get the lib bundled in the jar (below) and extract it.
tasks.withType<Test>().configureEach {
    dependsOn(cmakeBuild)
    systemProperty("quickjs.native.dir", quickJsJniDir.get().asFile.absolutePath)
}

// Bundle the built lib into the jvm jar as a resource under jni/<os>_<arch>/, so any consumer that
// `implementation(project(":quickjs"))`s it gets the lib on their classpath and the loader extracts
// it (see Loader.jvm.kt). CMake builds only the *current* host, so only the host folder is filled —
// shipping every desktop arch would need cross-compilation (as quickjs-kt/Zipline do with Zig).
val hostJniFolder: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val osPrefix = when {
        os.contains("mac") || os.contains("osx") -> "macos"
        os.contains("linux") -> "linux"
        os.contains("win") -> "windows"
        else -> error("Unsupported OS: $os")
    }
    val archSuffix = when (arch) {
        "aarch64" -> "aarch64"
        "amd64", "x86_64" -> "x64"
        else -> error("Unsupported arch: $arch")
    }
    "${osPrefix}_$archSuffix"
}
val jvmJniResourcesDir = layout.buildDirectory.dir("generated/jvmJniResources")
val bundleJvmJniLib by tasks.registering(Copy::class) {
    dependsOn(cmakeBuild)
    from(quickJsJniDir) { include(System.mapLibraryName("quickjsjni")) }
    into(jvmJniResourcesDir.map { it.dir("jni/$hostJniFolder") })
}
kotlin.sourceSets.getByName("jvmMain").resources.srcDir(files(jvmJniResourcesDir).builtBy(bundleJvmJniLib))

// ---------------------------------------------------------------------------------------------
// Android JNI: build libquickjsjni.so per ABI with the NDK and stage into androidMain/jniLibs.
// ---------------------------------------------------------------------------------------------
val androidAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val ndkDir: File? = run {
    val sdk = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: File(System.getProperty("user.home"), "Library/Android/sdk").takeIf { it.exists() }?.path
    sdk?.let { File(it, "ndk").listFiles()?.filter { d -> d.isDirectory }?.maxByOrNull { d -> d.name } }
}

val stageAndroidJni by tasks.registering {
    // Staged into the library jniLibs (for consumers) and the device-test jniLibs (for our emulator run).
    val jniLibsDirs = listOf(
        projectDir.resolve("src/androidMain/jniLibs"),
        projectDir.resolve("src/androidDeviceTest/jniLibs"),
    )
    inputs.dir(nativeSrc)
    outputs.dirs(jniLibsDirs)
    doLast {
        val ndk = ndkDir ?: error("Android NDK not found (set ANDROID_HOME with an installed NDK)")
        val toolchain = File(ndk, "build/cmake/android.toolchain.cmake")
        fun sh(vararg args: String) {
            val p = ProcessBuilder(*args).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0) throw GradleException("command failed: ${args.joinToString(" ")}\n$out")
        }
        androidAbis.forEach { abi ->
            val buildDir = layout.buildDirectory.dir("quickjs-android/$abi").get().asFile
            buildDir.mkdirs()
            sh(
                "cmake", "-S", nativeSrc.absolutePath, "-B", buildDir.absolutePath,
                "-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}",
                "-DANDROID_ABI=$abi", "-DANDROID_PLATFORM=android-${Versions.minSdk}",
                "-DCMAKE_BUILD_TYPE=Release",
            )
            sh("cmake", "--build", buildDir.absolutePath, "-j", "4")
            val so = File(buildDir, "libquickjsjni.so")
            jniLibsDirs.forEach { dir -> so.copyTo(File(dir, "$abi/libquickjsjni.so"), overwrite = true) }
        }
    }
}

// The .so must exist before anything compiles the device test or merges native libs (main or test,
// any variant) into the (test) APK.
tasks.matching {
    it.name == "compileAndroidDeviceTest" || it.name.matches(Regex("merge.*JniLibFolders"))
}.configureEach { dependsOn(stageAndroidJni) }

// ---------------------------------------------------------------------------------------------
// Test engine JS: this module ships NO JS — QuickJsRuntime.create requires the caller to supply
// the script (the :engine-js bundle). Only the TESTS need one, so the :engine-js TEST bundle
// (production + fixtures) is embedded, escape-only, into a commonTest const.
// ---------------------------------------------------------------------------------------------
val testEngineJs by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    testEngineJs(project(":engine-js", "engineTestJs"))
}
val testEngineJsKtDir = layout.buildDirectory.dir("generated/testEngineJs")

val generateTestEngineJs by tasks.registering {
    inputs.files(testEngineJs)
    outputs.dir(testEngineJsKtDir)
    doLast {
        val escaped = testEngineJs.singleFile.readText()
            .replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\$", "\${'\$'}").replace("\n", "\\n").replace("\t", "\\t")
        val dir = testEngineJsKtDir.get().asFile.resolve("ymusic/quickjs").apply { mkdirs() }
        dir.resolve("TestEngineJs.kt").writeText(
            """
            |package ymusic.quickjs
            |
            |// GENERATED — do not edit. The :engine-js bundle, for tests only.
            |internal const val TEST_ENGINE_JS: String = "$escaped"
            |""".trimMargin(),
        )
    }
}

kotlin.sourceSets.getByName("commonTest").kotlin.srcDir(files(testEngineJsKtDir).builtBy(generateTestEngineJs))
