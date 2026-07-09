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
                // api: EngineError implements kmpcommon.error.CodedError; call() takes a JsonObject
                api(project(":kmpCommon"))
                api(Libs.Kotlinx.serializationJson)
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

// JS preambles: authored (with comments) in js/*.js, minified at build time with Closure Compiler
// (SIMPLE, no transpile) and emitted as generated Kotlin consts the engine loads. Mirrors
// :quickjs's bootstrap handling — globals (HttpRequest, serve, …) and property names are preserved.
val jsPreambles = listOf(
    "http.js" to "HTTP_JS",
    "serve.js" to "SERVE_JS",
)
val preambleKtDir = layout.buildDirectory.dir("generated/httpJs")

val generateJsPreambles by tasks.registering {
    for ((jsName, _) in jsPreambles) inputs.file(layout.projectDirectory.file("js/$jsName"))
    outputs.dir(preambleKtDir)
    doLast {
        preambleKtDir.get().asFile.deleteRecursively() // no stale consts from renamed preambles
        val dir = preambleKtDir.get().asFile.resolve("ymusic/dynamicengine/internal").apply { mkdirs() }
        for ((jsName, constName) in jsPreambles) {
            val options = CompilerOptions().apply {
                setLanguageIn(CompilerOptions.LanguageMode.ECMASCRIPT_NEXT)
                setLanguageOut(CompilerOptions.LanguageMode.NO_TRANSPILE) // keep async/await, classes
            }
            CompilationLevel.SIMPLE_OPTIMIZATIONS.setOptionsForCompilationLevel(options)
            WarningLevel.QUIET.setOptionsForWarningLevel(options)
            val compiler = Compiler()
            val result = compiler.compile(
                emptyList<SourceFile>(), // externs (none) — undeclared globals (ymix, …) preserved
                listOf(SourceFile.fromCode(jsName, layout.projectDirectory.file("js/$jsName").asFile.readText())),
                options,
            )
            if (!result.success) {
                throw GradleException("$jsName minify failed:\n" + compiler.errors.joinToString("\n"))
            }
            val minified = compiler.toSource()
            check(minified.isNotBlank()) { "closure produced empty output for $jsName" }
            val escaped = minified
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\$", "\${'\$'}").replace("\n", "\\n").replace("\t", "\\t")
            val ktName = constName.split("_").joinToString("") { part ->
                part.lowercase().replaceFirstChar { it.uppercase() }
            }
            dir.resolve("${ktName}Preamble.kt").writeText(
                """
                |package ymusic.dynamicengine.internal
                |
                |// GENERATED — do not edit. Minified from js/$jsName (Closure Compiler) at build time.
                |internal const val $constName: String = "$escaped"
                |""".trimMargin(),
            )
        }
    }
}

kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(files(preambleKtDir).builtBy(generateJsPreambles))
