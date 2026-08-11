package io.ymusic.quickjs

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * Load the `quickjsjni` shared library. Cascade (mirrors quickjs-kt / AndroidX sqlite-bundled):
 *  1. `-Dquickjs.native.dir` — an explicit dir. The Gradle test tasks set this, so our own build
 *     loop loads straight from the CMake output with no extraction.
 *  2. `System.loadLibrary` — a copy already on `java.library.path`.
 *  3. Extract the lib bundled in the jar (`jni/<os>_<arch>/lib…`, put there by `bundleJvmJniLib`) to
 *     a temp file and `System.load` it — the path any downstream consumer hits, since the artifact
 *     carries the lib and needs no build wiring on their side.
 */
internal actual fun loadQuickJsLibrary() {
    val name = "quickjsjni"
    val libFileName = System.mapLibraryName(name)

    System.getProperty("quickjs.native.dir")?.let { dir ->
        System.load(File(dir, libFileName).absolutePath)
        return
    }

    try {
        System.loadLibrary(name)
        return
    } catch (_: UnsatisfiedLinkError) {
        // Not preinstalled on java.library.path — fall through to the bundled copy.
    }

    val resource = "jni/${hostJniFolder()}/$libFileName"
    val classLoader = LoaderAnchor::class.java.classLoader
        ?: error("No class loader available to read '$resource'")
    val extracted = classLoader.getResourceAsStream(resource)?.use { stream ->
        val suffix = "." + libFileName.substringAfterLast('.', "so")
        val tmp = Files.createTempFile("quickjsjni", suffix).also { it.toFile().deleteOnExit() }
        Files.copy(stream, tmp, StandardCopyOption.REPLACE_EXISTING)
        tmp
    } ?: error(
        "No bundled quickjsjni for this host at resource '$resource'. " +
            "The :quickjs jar bundles only the build host's arch (CMake builds one host).",
    )
    @Suppress("UnsafeDynamicallyLoadedCode")
    System.load(extracted.toAbsolutePath().toString())
}

/** Maps the running host to the jar resource folder the libs are bundled under (see build script). */
private fun hostJniFolder(): String {
    val os = System.getProperty("os.name").lowercase(Locale.US)
    val arch = System.getProperty("os.arch").lowercase(Locale.US)
    val osPrefix = when {
        os.contains("mac") || os.contains("osx") -> "macos"
        os.contains("linux") -> "linux"
        os.contains("win") -> "windows"
        else -> error("Unsupported operating system: $os")
    }
    val archSuffix = when (arch) {
        "aarch64" -> "aarch64"
        "amd64", "x86_64" -> "x64"
        else -> error("Unsupported architecture: $arch")
    }
    return "${osPrefix}_$archSuffix"
}

/** Anchor for `classLoader` — resolves the jar resource next to this module's classes. */
private class LoaderAnchor
