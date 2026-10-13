package io.ymusic.ui

/** One third-party component surfaced in Settings → Open source licenses. [scope] marks
 *  platform-specific entries ("Android", "Desktop", "iOS"); null = every platform. [url] is where
 *  its source and licence text live. */
internal data class OssComponent(val name: String, val license: String, val url: String, val scope: String? = null)

/**
 * The shipped third-party components, hand-maintained (there is no license-plugin sweep in this
 * build): update alongside buildSrc/Dependencies.kt and the apps' resolved runtime classpaths.
 * Dev/test/build-only deps (JUnit, Moshi, espresso, kotlinx-benchmark, esbuild, Closure Compiler)
 * and annotation-only jars are deliberately absent — they are not distributed with the app.
 *
 * FFmpeg's libraries are linked into the app: shared on Android and desktop (the MSE player and
 * the packed CLI load the same libav* DSOs), static on iOS. That is compatible because the app is
 * GPL-3.0 and every FFmpeg component here is LGPL-2.1-or-later / LGPL-2.0-or-later (built without
 * --enable-gpl or --enable-nonfree), which the GPL-3.0 combination may take. The corresponding
 * source of the FFmpeg build — scripts, pinned sources, patches — is github.com/ymusic-io/ffmpeg-build.
 */
internal val OssComponents: List<OssComponent> = listOf(
    OssComponent("Kotlin Standard Library", "Apache-2.0", "https://github.com/JetBrains/kotlin"),
    OssComponent("kotlinx.coroutines", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
    OssComponent("kotlinx.serialization", "Apache-2.0", "https://github.com/Kotlin/kotlinx.serialization"),
    OssComponent("kotlinx-datetime", "Apache-2.0", "https://github.com/Kotlin/kotlinx-datetime"),
    OssComponent("kotlinx-atomicfu", "Apache-2.0", "https://github.com/Kotlin/kotlinx-atomicfu"),
    OssComponent(
        "Compose Multiplatform / Jetpack Compose (incl. Material 3, Material Icons)",
        "Apache-2.0",
        "https://github.com/JetBrains/compose-multiplatform",
    ),
    OssComponent("Okio", "Apache-2.0", "https://github.com/square/okio"),
    OssComponent("OkHttp (and kmpHttp's port of it)", "Apache-2.0", "https://github.com/square/okhttp"),
    OssComponent("SQLDelight", "Apache-2.0", "https://github.com/sqldelight/sqldelight"),
    OssComponent("SQLite", "Public Domain", "https://sqlite.org"),
    OssComponent("QuickJS (Fabrice Bellard, Charlie Gordon)", "MIT", "https://bellard.org/quickjs/"),
    // One line per licence, because they differ: FFmpeg is LGPL-2.1+, the two encoders statically
    // linked into it are LGPL-2.0+. Verified against `ffmpeg -L` and each library's own COPYING.
    OssComponent("FFmpeg (libav* libraries, ffmpeg CLI)", "LGPL-2.1+", "https://github.com/ymusic-io/ffmpeg-build"),
    OssComponent("libvpx (linked into FFmpeg)", "BSD-3-Clause", "https://chromium.googlesource.com/webm/libvpx"),
    OssComponent("dav1d (linked into FFmpeg)", "BSD-2-Clause", "https://code.videolan.org/videolan/dav1d", scope = "Desktop / iOS"),
    OssComponent("LAME (linked into FFmpeg)", "LGPL-2.0+", "https://lame.sourceforge.io", scope = "Android / Desktop"),
    OssComponent("Shine (linked into FFmpeg)", "LGPL-2.0+", "https://github.com/toots/shine", scope = "Android / Desktop"),
    OssComponent("NVIDIA codec headers (in FFmpeg)", "MIT", "https://github.com/FFmpeg/nv-codec-headers", scope = "Desktop (Linux)"),
    OssComponent("Vulkan-Headers (in FFmpeg)", "Apache-2.0", "https://github.com/KhronosGroup/Vulkan-Headers", scope = "Desktop (Linux)"),
    OssComponent("Sentry SDK", "MIT", "https://github.com/getsentry/sentry-kotlin-multiplatform", scope = "Android / Desktop"),
    OssComponent("ZXing", "Apache-2.0", "https://github.com/zxing/zxing", scope = "Android / Desktop"),
    OssComponent("Skiko / Skia", "Apache-2.0 / BSD-3-Clause", "https://github.com/JetBrains/skiko", scope = "Desktop / iOS"),
    OssComponent("AndroidX", "Apache-2.0", "https://android.googlesource.com/platform/frameworks/support", scope = "Android"),
    OssComponent("Media3 / ExoPlayer", "Apache-2.0", "https://github.com/androidx/media", scope = "Android"),
    OssComponent("Guava", "Apache-2.0", "https://github.com/google/guava", scope = "Android"),
    OssComponent("epitaph", "MIT", "https://github.com/abovevacant/epitaph", scope = "Android"),
    OssComponent(
        "url-search-params-polyfill",
        "MIT",
        "https://github.com/jerrybendy/url-search-params-polyfill",
        scope = "Android",
    ),
    OssComponent("OpenJDK runtime", "GPL-2.0 with Classpath Exception", "https://openjdk.org", scope = "Desktop"),
    OssComponent("JetBrains Runtime API", "Apache-2.0", "https://github.com/JetBrains/JetBrainsRuntimeApi", scope = "Desktop"),
    OssComponent("sqlite-jdbc (Xerial)", "Apache-2.0", "https://github.com/xerial/sqlite-jdbc", scope = "Desktop"),
    OssComponent("Java Native Access (JNA)", "Apache-2.0 / LGPL-2.1+", "https://github.com/java-native-access/jna", scope = "Desktop"),
    OssComponent("normalize (Doist)", "MIT", "https://github.com/Doist/doistx-normalize", scope = "iOS"),
    OssComponent("Public Suffix List", "MPL-2.0", "https://publicsuffix.org", scope = "iOS"),
)
