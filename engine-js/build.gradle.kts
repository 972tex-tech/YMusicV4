import com.google.javascript.jscomp.CheckLevel
import com.google.javascript.jscomp.CommandLineRunner
import com.google.javascript.jscomp.CompilationLevel
import com.google.javascript.jscomp.Compiler
import com.google.javascript.jscomp.CompilerOptions
import com.google.javascript.jscomp.DiagnosticGroups
import com.google.javascript.jscomp.SourceFile

// All engine JS in one place, built into single artifacts shipped with the app and remotely
// replaceable as a whole. Pipeline: tsc --noEmit (typecheck) -> esbuild (bundle only, no minify)
// -> Closure Compiler ADVANCED (minify + full property mangling). Closure knows the stdlib via its
// builtin externs, so EVERYTHING internal mangles — identifiers and property names alike; the only
// preserved names are externs.js (the C natives, the __js* transport globals, `versions`) and
// quoted wire keys (Closure never renames quoted properties; the coding standard writes all wire
// objects quoted/bracketed).
//
// Two artifacts, same pipeline, both ADVANCED: engine.js (production, src/index.ts) and
// engine-test.js (src/test/index.ts = production + pipe-driven test fixtures).

// Closure Compiler (pure-JVM) on the build classpath, only for this module.
buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("com.google.javascript:closure-compiler:v20260629") }
}

plugins {
    base
}

// The bundle's own revision — BUMP MANUALLY on every remote push. Inlined by esbuild --define
// (`declare const` in types.d.ts): the "init" health check reports it; the host logs it on swaps
// and stamps it into error reports, so a bad push is attributable per revision.
val backendVersion = 20

// Compile-time define: a bare `declare const` identifier substituted inline at bundle time —
// no runtime global, no extern.
val esbuildDefines = arrayOf("--define:BACKEND_VERSION=$backendVersion")

// Size budgets for the shipped artifacts, in bytes. The bundle ships embedded in the app AND
// travels over the network on every remote swap, so growth is a product decision: crossing a budget
// FAILS the build, and raising the number here is how that decision gets made (and reviewed).
// A build within 10% of its budget warns, so the ceiling is never a surprise.
val bundleBudgets = mapOf("engine.js" to 72 * 1024L, "engine-test.js" to 104 * 1024L)

val srcDir = layout.projectDirectory.dir("src")
val tsconfig = layout.projectDirectory.file("tsconfig.json")
val externsJs = layout.projectDirectory.file("externs.js")
val rawBundleFile = layout.buildDirectory.file("raw/engine.raw.js")
val rawBundleMeta = layout.buildDirectory.file("raw/engine.meta.json")
val rawTestBundleMeta = layout.buildDirectory.file("raw/engine-test.meta.json")
val sizeReportDir = layout.buildDirectory.dir("reports/engine-js")
val bundleFile = layout.buildDirectory.file("dist/engine.js")
val bundleMapFile = layout.buildDirectory.file("dist/engine.js.map")
val testBundleFile = layout.buildDirectory.file("dist/engine-test.js")

val typecheckJs by tasks.registering(Exec::class) {
    inputs.dir(srcDir)
    inputs.file(tsconfig)
    outputs.file(layout.buildDirectory.file("typecheck.ok"))
    workingDir = projectDir
    commandLine("npx", "-y", "-p", "typescript@${Versions.typescript}", "tsc", "--noEmit", "-p", ".")
    doLast { outputs.files.singleFile.writeText("ok") }
}

// esbuild resolves TS + modules into one plain script. target=esnext ON PURPOSE: lowering would
// rewrite class fields into __publicField(this, "_x", …) — a QUOTED define whose dotted accesses
// Closure then renames apart (a split property = undefined at runtime). Closure parses the native
// syntax fine. No minification here — Closure owns that.
val bundleRawJs by tasks.registering(Exec::class) {
    dependsOn(typecheckJs)
    inputs.dir(srcDir)
    inputs.property("defines", esbuildDefines.joinToString(" ")) // Exec doesn't track commandLine
    outputs.files(rawBundleFile, rawBundleMeta)
    workingDir = projectDir
    commandLine(
        "npx", "-y", "esbuild@${Versions.esbuild}",
        "src/index.ts", "--bundle",
        "--format=iife", "--target=esnext", "--charset=utf8",
        *esbuildDefines,
        "--metafile=${rawBundleMeta.get().asFile.absolutePath}",
        "--outfile=${rawBundleFile.get().asFile.absolutePath}",
    )
}

// One Closure invocation, shared by both artifacts. Level ADVANCED = full mangling (production);
// SIMPLE = locals only, globals/properties preserved — the interim level for the test bundle so
// the externally-evaled test snippets keep working while they migrate to fixtures, with Closure's
// parser/emitter still validating the output every build. The fixture-migration end state flips
// the test bundle to ADVANCED too.
// What went INTO the raw bundle, per source directory (esbuild's metafile): the shape of the
// growth. Closure's output has no module boundaries left, so the breakdown can only be measured
// before it — the ratio to the final size is what it is, but the ranking survives minification.
fun sourceBreakdown(meta: java.io.File): List<Pair<String, Long>> {
    @Suppress("UNCHECKED_CAST")
    val json = groovy.json.JsonSlurper().parse(meta) as Map<String, Any>

    @Suppress("UNCHECKED_CAST")
    val outputs = (json["outputs"] as Map<String, Map<String, Any>>).values
        .first { (it["inputs"] as? Map<*, *>)?.isNotEmpty() == true }

    @Suppress("UNCHECKED_CAST")
    val inputs = outputs["inputs"] as Map<String, Map<String, Any>>
    return inputs.entries
        .groupBy { it.key.substringBeforeLast('/') }
        .map { (dir, entries) -> dir to entries.sumOf { (it.value["bytesInOutput"] as Number).toLong() } }
        .sortedByDescending { it.second }
}

fun enforceBudget(out: java.io.File, meta: java.io.File) {
    val budget = bundleBudgets.getValue(out.name)
    val size = out.length()
    val report = buildString {
        appendLine("${out.name}: $size bytes / $budget budget (${size * 100 / budget}%)")
        sourceBreakdown(meta).forEach { (dir, bytes) -> appendLine("  %7d  %s".format(bytes, dir)) }
        appendLine("  (pre-Closure source bytes, from esbuild's metafile)")
    }
    sizeReportDir.get().asFile.apply { mkdirs() }.resolve("${out.name}.size.txt").writeText(report)
    logger.lifecycle(report.trimEnd())
    if (size > budget) {
        throw GradleException(
            "${out.name} is $size bytes, over its $budget-byte budget. The bundle ships embedded in " +
                "the app and travels on every remote swap — either shrink it, or raise the budget in " +
                "engine-js/build.gradle.kts deliberately.\n$report",
        )
    }
    if (size * 10 > budget * 9) {
        logger.warn("WARNING: ${out.name} is within 10% of its $budget-byte budget.")
    }
}

fun closureCompile(raw: java.io.File, out: java.io.File, map: java.io.File, meta: java.io.File, level: CompilationLevel) {
    val options = CompilerOptions().apply {
        setLanguageIn(CompilerOptions.LanguageMode.ECMASCRIPT_NEXT)
        setLanguageOut(CompilerOptions.LanguageMode.NO_TRANSPILE)
        // CUSTOM = pure-ECMAScript builtin externs (no DOM) — QuickJS has no browser surface,
        // and anything not in externs is fair game for renaming.
        setEnvironment(CompilerOptions.Environment.CUSTOM)
        sourceMapOutputPath = map.absolutePath
    }
    level.setOptionsForCompilationLevel(options)
    // ADVANCED enables quoted->dotted normalization, which would feed the QUOTED wire keys into
    // the renamer and corrupt every JSON contract with the host. Quoted must stay quoted.
    options.setConvertToDottedProperties(false)
    // esnext class fields (assigned via field syntax) false-positive this check en masse;
    // renaming stays consistent regardless. Undefined-variable checks (missing externs) remain.
    options.setWarningLevel(DiagnosticGroups.MISSING_PROPERTIES, CheckLevel.OFF)
    // The input is TS with types ERASED (~64% inferable): the type-BASED property passes split
    // one runtime property into several names on bad inference (observed: _headers in build()).
    // Name-based renaming (same name -> same rename, everywhere) is the sound mode here.
    options.setDisambiguateProperties(false)
    options.setAmbiguateProperties(false)
    options.setInlineProperties(false)
    val externs = CommandLineRunner.getBuiltinExterns(options.environment) +
        SourceFile.fromFile(externsJs.asFile.absolutePath)
    val compiler = Compiler()
    val result = compiler.compile(externs, listOf(SourceFile.fromFile(raw.absolutePath)), options)
    // Warnings surface loudly: an undeclared name here usually means a missing extern — under
    // ADVANCED that is a future runtime breakage, never noise.
    result.warnings.forEach { logger.warn("closure: $it") }
    if (!result.success) {
        throw GradleException("closure $level failed:\n" + result.errors.joinToString("\n"))
    }
    out.parentFile.mkdirs()
    val minified = compiler.toSource()
    check(minified.isNotBlank()) { "closure produced empty output" }
    out.writeText(minified + "\n//# sourceMappingURL=${map.name}\n")
    map.writer().use { compiler.sourceMap.appendTo(it, out.name) }
    enforceBudget(out, meta)
}

val closureJs by tasks.registering {
    dependsOn(bundleRawJs)
    inputs.file(rawBundleFile)
    inputs.file(externsJs)
    inputs.property("budget", bundleBudgets.getValue("engine.js"))
    outputs.files(bundleFile, bundleMapFile)
    doLast {
        closureCompile(
            rawBundleFile.get().asFile,
            bundleFile.get().asFile,
            bundleMapFile.get().asFile,
            rawBundleMeta.get().asFile,
            CompilationLevel.ADVANCED_OPTIMIZATIONS,
        )
    }
}

// The TEST bundle: production + fixtures, same pipeline and same ADVANCED level — tests run on
// output as mangled as production's. The few remaining load() snippets touch only externed
// natives, which is exactly what survives.
val rawTestBundleFile = layout.buildDirectory.file("raw/engine-test.raw.js")
val testBundleMapFile = layout.buildDirectory.file("dist/engine-test.js.map")

val bundleRawTestJs by tasks.registering(Exec::class) {
    dependsOn(typecheckJs)
    inputs.dir(srcDir)
    inputs.property("defines", esbuildDefines.joinToString(" ")) // Exec doesn't track commandLine
    outputs.files(rawTestBundleFile, rawTestBundleMeta)
    workingDir = projectDir
    commandLine(
        "npx", "-y", "esbuild@${Versions.esbuild}",
        "src/test/index.ts", "--bundle",
        "--format=iife", "--target=esnext", "--charset=utf8",
        *esbuildDefines,
        "--metafile=${rawTestBundleMeta.get().asFile.absolutePath}",
        "--outfile=${rawTestBundleFile.get().asFile.absolutePath}",
    )
}

val bundleTestJs by tasks.registering {
    dependsOn(bundleRawTestJs)
    inputs.file(rawTestBundleFile)
    inputs.file(externsJs)
    inputs.property("budget", bundleBudgets.getValue("engine-test.js"))
    outputs.files(testBundleFile, testBundleMapFile)
    doLast {
        closureCompile(
            rawTestBundleFile.get().asFile,
            testBundleFile.get().asFile,
            testBundleMapFile.get().asFile,
            rawTestBundleMeta.get().asFile,
            CompilationLevel.ADVANCED_OPTIMIZATIONS,
        )
    }
}

tasks.named("assemble") { dependsOn(closureJs, bundleTestJs) }

// Consumable artifacts: engineJs = the production bundle (:dynamic-engine embeds it as the
// baseline ENGINE_JS); engineTestJs = the test bundle (:quickjs / :dynamic-engine test consts).
val engineJs by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
val engineTestJs by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts {
    add(engineJs.name, bundleFile) { builtBy(closureJs) }
    add(engineTestJs.name, testBundleFile) { builtBy(bundleTestJs) }
}
