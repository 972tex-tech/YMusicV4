package io.ymusic.ui

/** One third-party component surfaced in Settings → Open source licenses. [scope] marks
 *  platform-specific entries ("Android", "Desktop", "iOS"); null = every platform. */
internal data class OssComponent(val name: String, val license: String, val scope: String? = null)

/**
 * The shipped third-party components, hand-maintained (there is no license-plugin sweep in this
 * build): update alongside buildSrc/Dependencies.kt. Dev/test/benchmark-only deps (JUnit, Moshi,
 * espresso, kotlinx-benchmark) are deliberately absent — they are not distributed with the app.
 * FFmpeg ships as a SEPARATE executable (never linked) built LGPL with LGPL lame/shine.
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
    OssComponent("FFmpeg (separate executable, with LAME and Shine)", "LGPL-2.1+", scope = "Android / Desktop"),
    OssComponent("SQLite", "Public Domain"),
    OssComponent("AndroidX (core, lifecycle, activity)", "Apache-2.0", scope = "Android"),
    OssComponent("Media3 / ExoPlayer", "Apache-2.0", scope = "Android"),
    OssComponent("sqlite-jdbc (Xerial)", "Apache-2.0", scope = "Desktop"),
    OssComponent("Skiko / Skia", "Apache-2.0 / BSD-3-Clause", scope = "Desktop"),
    OssComponent("JCEF / CEF / Chromium", "BSD-3-Clause", scope = "Desktop"),
    OssComponent("jcefmaven", "Apache-2.0", scope = "Desktop"),
    OssComponent("Java Native Access (JNA)", "Apache-2.0", scope = "Desktop"),
    OssComponent("vlcj", "GPL-3.0", scope = "Desktop"),
    OssComponent("libVLC (system install)", "LGPL-2.1+", scope = "Desktop"),
    OssComponent("libcurl", "curl license", scope = "iOS"),
    OssComponent("sentry-cocoa", "MIT", scope = "iOS"),
)
