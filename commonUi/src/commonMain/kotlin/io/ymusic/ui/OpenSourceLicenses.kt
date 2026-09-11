package io.ymusic.ui

/** One third-party component surfaced in Settings → Open source licenses. [scope] marks
 *  platform-specific entries ("Android", "Desktop", "iOS"); null = every platform. */
internal data class OssComponent(val name: String, val license: String, val scope: String? = null)

/**
 * The shipped third-party components, hand-maintained (there is no license-plugin sweep in this
 * build): update alongside buildSrc/Dependencies.kt. Dev/test/benchmark-only deps (JUnit, Moshi,
 * espresso, kotlinx-benchmark) are deliberately absent — they are not distributed with the app.
 * FFmpeg ships as a SEPARATE executable the app runs as a child process — never linked in — so its
 * LGPL and this app's GPL never meet: the boundary is a process, not a library call. The build
 * scripts, the sources it is built from and the relinking notice the LGPL asks for live in
 * github.com/ymusic-io/ffmpeg-build (NOTICE.md); the binaries are that repo's release assets.
 */
internal val OssComponents: List<OssComponent> = listOf(
    OssComponent("Kotlin Standard Library", "Apache-2.0"),
    OssComponent("kotlinx.coroutines", "Apache-2.0"),
    OssComponent("kotlinx.serialization", "Apache-2.0"),
    OssComponent("kotlinx-datetime", "Apache-2.0"),
    OssComponent("kotlinx-atomicfu", "Apache-2.0"),
    OssComponent("Compose Multiplatform / Jetpack Compose", "Apache-2.0"),
    OssComponent("Okio", "Apache-2.0"),
    OssComponent("OkHttp", "Apache-2.0"),
    OssComponent("SQLDelight", "Apache-2.0"),
    OssComponent("QuickJS (Fabrice Bellard, Charlie Gordon)", "MIT"),
    OssComponent("Sentry SDK", "MIT"),
    OssComponent("normalize (Doist)", "MIT"),
    // One line per licence, because they differ: FFmpeg is LGPL-2.1+, the two encoders statically
    // linked into it are LGPL-2.0+. Verified against `ffmpeg -L` and each library's own COPYING —
    // the build enables neither --enable-gpl nor --enable-nonfree, so nothing pulls it to GPL.
    OssComponent("FFmpeg (separate executable)", "LGPL-2.1+", scope = "Android / Desktop"),
    OssComponent("LAME (linked into FFmpeg)", "LGPL-2.0+", scope = "Android / Desktop"),
    OssComponent("Shine (linked into FFmpeg)", "LGPL-2.0+", scope = "Android / Desktop"),
    OssComponent("SQLite", "Public Domain"),
    OssComponent("AndroidX (core, lifecycle, activity)", "Apache-2.0", scope = "Android"),
    OssComponent("Media3 / ExoPlayer", "Apache-2.0", scope = "Android"),
    OssComponent("sqlite-jdbc (Xerial)", "Apache-2.0", scope = "Desktop"),
    OssComponent("Skiko / Skia", "Apache-2.0 / BSD-3-Clause", scope = "Desktop"),
    OssComponent("Java Native Access (JNA)", "Apache-2.0", scope = "Desktop"),
    OssComponent("vlcj", "GPL-3.0", scope = "Desktop"),
    // One icon (cone-striped), vendored as an ImageVector on the first-run screen. MIT requires the
    // notice to travel with binaries, not just with source, which is what this line is for.
    OssComponent("Bootstrap Icons", "MIT", scope = "Desktop"),
    OssComponent("libVLC (system install)", "LGPL-2.1+", scope = "Desktop"),
    OssComponent("libcurl", "curl license", scope = "iOS"),
    OssComponent("sentry-cocoa", "MIT", scope = "iOS"),
)
