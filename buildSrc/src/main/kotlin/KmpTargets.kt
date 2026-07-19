import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

private val KotlinProjectExtension.isHostCompatibleMode
    get() = project.findProperty("kmpTargets") == "host"

val hostOs: org.gradle.internal.os.OperatingSystem =
    org.gradle.internal.os.OperatingSystem.current()
val hostIsArm64 = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }

fun KotlinMultiplatformExtension.withAppleTargets(
    configure: KotlinNativeTarget.() -> Unit = {}
) {
    if (hostOs.isMacOsX && hostIsArm64 || !isHostCompatibleMode) {
        listOf(
            iosArm64(),
            iosSimulatorArm64(),
            macosArm64(),

            tvosArm64(),
            tvosSimulatorArm64(),

            watchosArm64(),
//            watchosArm32(),
            watchosDeviceArm64(),
            watchosSimulatorArm64(),
        ).forEach(configure)
    }

    if (hostOs.isMacOsX && !hostIsArm64 || !isHostCompatibleMode) {
        listOf(
            iosX64(),
            macosX64(),
            tvosX64(),
            watchosX64(),
        ).forEach(configure)
    }
}

fun KotlinMultiplatformExtension.withLinuxTargets(
    configure: KotlinNativeTarget.() -> Unit = {}
) {
    if (hostOs.isLinux && hostIsArm64 || !isHostCompatibleMode) {
        linuxArm64().configure()
    }

    if (hostOs.isLinux && !hostIsArm64 || !isHostCompatibleMode) {
        linuxX64().configure()
    }
}

/**
 * iOS-only native targets (host-gated): device + simulator, no tvOS/watchOS. For modules whose
 * native dependency publishes iOS but not tvOS/watchOS (e.g. :dynamic-engine over quickjs-kt).
 * Compose with [withMacosTargets] / [withLinuxTargets] to cover the rest of that dependency's matrix.
 */
fun KotlinMultiplatformExtension.withIosTargets(
    configure: KotlinNativeTarget.() -> Unit = {}
) {
    if (hostOs.isMacOsX && hostIsArm64 || !isHostCompatibleMode) {
        listOf(iosArm64(), iosSimulatorArm64()).forEach(configure)
    }
    if (hostOs.isMacOsX && !hostIsArm64 || !isHostCompatibleMode) {
        iosX64().configure()
    }
}

/**
 * iOS targets for a **Compose Multiplatform** module: `iosArm64` + `iosSimulatorArm64` only, never
 * `iosX64`. Compose MP 1.11.1 dropped all x86_64-Apple targets, so a Compose module can't take the
 * Intel-host iosX64 path the non-Compose helpers use — on an Intel host it builds iOS **only** in
 * all-targets mode (`-PkmpTargets=all`, cross-compiling arm64 for a real device), never in the
 * default host mode. On Apple Silicon it configures them normally. Keeps the targets in sync with
 * both Compose (arm64-only) and the host-gated backend modules' all-mode matrix.
 */
fun KotlinMultiplatformExtension.withComposeIosTargets(
    configure: KotlinNativeTarget.() -> Unit = {}
) {
    if (hostOs.isMacOsX && hostIsArm64 || !isHostCompatibleMode) {
        listOf(iosArm64(), iosSimulatorArm64()).forEach(configure)
    }
}

/**
 * macOS-only native targets (host-gated). For modules that only need to run/build on macOS, e.g.
 * the benchmark module. Keeps the configured targets in sync with the host-gated library modules
 * they depend on.
 */
fun KotlinMultiplatformExtension.withMacosTargets(
    configure: KotlinNativeTarget.() -> Unit = {}
) {
    if (hostOs.isMacOsX && hostIsArm64 || !isHostCompatibleMode) {
        macosArm64().configure()
    }
    if (hostOs.isMacOsX && !hostIsArm64 || !isHostCompatibleMode) {
        macosX64().configure()
    }
}

/**
 * Desktop-native targets only: macOS + Linux, no iOS/tvOS/watchOS. Used by modules that wrap a
 * desktop-only native library (e.g. :kmpHttp:curl over libcurl). Host-gated the same way as the
 * other helpers so a given host only configures the native targets it can actually build — this is
 * what keeps macOS from trying to generate the Linux cinterop (and vice-versa).
 */
fun KotlinMultiplatformExtension.withDesktopTargets(
    configure: KotlinNativeTarget.() -> Unit = {}
) {
    withMacosTargets(configure)
    withLinuxTargets(configure)
}

fun KotlinMultiplatformExtension.kmpHttpCommonConfig() {
    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    sourceSets.all {
        languageSettings.optIn("kotlin.ExperimentalMultiplatform")
    }
}
