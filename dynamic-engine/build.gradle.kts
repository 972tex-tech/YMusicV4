@file:Suppress("UnstableApiUsage")

import com.google.javascript.jscomp.CompilationLevel
import com.google.javascript.jscomp.Compiler
import com.google.javascript.jscomp.CompilerOptions
import com.google.javascript.jscomp.SourceFile
import com.google.javascript.jscomp.WarningLevel
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Closure Compiler (pure-JVM JS minifier) on the build classpath, only for this module — used to
// minify the HTTP preamble at build time (see generateHttpJs). No Node/npm toolchain.
buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("com.google.javascript:closure-compiler:v20231112") }
}

plugins {
    kotlin("multiplatform")
    // AGP KMP plugin (same as :media-store / :kmpFfmpeg) — supplies the `android {}` KMP target.
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    android {
        namespace = "ymusic.dynamicengine"
        compileSdk = Versions.compileSdk
        minSdk = Versions.minSdk
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // Match :quickjs's target set (jvm/android/ios/macos/linux). withIosTargets() covers ios
    // (device + simulator); the rest comes from withMacosTargets() + withLinuxTargets(). Host-gated.
    // :quickjs is self-contained — its jar bundles the JNI lib and its klib embeds the static lib —
    // so consuming it needs no native-lib wiring here.
    withIosTargets()
    withMacosTargets()
    withLinuxTargets()

    applyDefaultHierarchyTemplate()

    sourceSets {
        /**
         * commonMain — the engine host: DynamicEngine wraps the :quickjs runtime and wires the host
         * protocols the scraping JS talks to (HTTP now; storage/fs/sqlite later). All platform work
         * lives in :quickjs, so this module is pure commonMain.
         */
        val commonMain by getting {
            dependencies {
                implementation(project(":quickjs"))
                // api: DynamicEngine.create takes a KmpHttpClient. :kmpHttp modules have no
                // android target — the android target resolves their jvm variants (for :core
                // that means real OkHttp), the same rule :kmpCommon relies on.
                api(project(":kmpHttp:core"))
                implementation(project(":kmpHttp:json"))
                implementation(Libs.Kotlinx.coroutinesCore)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(Libs.Kotlinx.coroutinesTest)
            }
        }
    }
}

// HTTP preamble: authored (with comments) in js/http.js, minified at build time with Closure
// Compiler (SIMPLE, no transpile) and emitted as a generated Kotlin const the engine loads. Mirrors
// :quickjs's bootstrap handling — globals (HttpRequest) and property names are preserved.
val httpJs = layout.projectDirectory.file("js/http.js")
val httpKtDir = layout.buildDirectory.dir("generated/httpJs")

val generateHttpJs by tasks.registering {
    inputs.file(httpJs)
    outputs.dir(httpKtDir)
    doLast {
        val options = CompilerOptions().apply {
            setLanguageIn(CompilerOptions.LanguageMode.ECMASCRIPT_NEXT)
            setLanguageOut(CompilerOptions.LanguageMode.NO_TRANSPILE) // keep async/await, classes
        }
        CompilationLevel.SIMPLE_OPTIMIZATIONS.setOptionsForCompilationLevel(options)
        WarningLevel.QUIET.setOptionsForWarningLevel(options)
        val compiler = Compiler()
        val result = compiler.compile(
            emptyList<SourceFile>(), // externs (none) — undeclared globals (ymix, …) preserved
            listOf(SourceFile.fromCode("http.js", httpJs.asFile.readText())),
            options,
        )
        if (!result.success) throw GradleException("http minify failed:\n" + compiler.errors.joinToString("\n"))
        val minified = compiler.toSource()
        check(minified.isNotBlank()) { "closure produced empty output" }
        val escaped = minified
            .replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\$", "\${'\$'}").replace("\n", "\\n").replace("\t", "\\t")
        val dir = httpKtDir.get().asFile.resolve("ymusic/dynamicengine/internal").apply { mkdirs() }
        dir.resolve("HttpPreamble.kt").writeText(
            """
            |package ymusic.dynamicengine.internal
            |
            |// GENERATED — do not edit. Minified from js/http.js (Closure Compiler) at build time.
            |internal const val HTTP_JS: String = "$escaped"
            |""".trimMargin(),
        )
    }
}

kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(files(httpKtDir).builtBy(generateHttpJs))
