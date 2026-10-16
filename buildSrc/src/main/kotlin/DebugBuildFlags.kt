import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Per-module `internal const val DEBUG_BUILD`, baked from `-Pymusic.debugLogs` (default true);
 * a production build ([isProductionBuild]) forces it false — it never reports as a debug one.
 * Emitted into that module's own root package (longest common prefix of its source packages).
 * Android app modules keep `BuildConfig.DEBUG` — this is not generated there.
 */
fun Project.installDebugBuildFlag() {
    val pkg = inferDebugBuildPackage() ?: return
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            sourceSets.named("commonMain") { kotlin.srcDir(debugBuildFlagTask(pkg).flatMap { it.outputDir }) }
        }
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            sourceSets.named("main") { kotlin.srcDir(debugBuildFlagTask(pkg).flatMap { it.outputDir }) }
        }
    }
}

abstract class GenerateDebugBuildFlag : DefaultTask() {
    @get:Input
    abstract val debugBuild: Property<Boolean>

    @get:Input
    abstract val packageName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val pkg = packageName.get()
        val file = outputDir.get().asFile.resolve(pkg.replace('.', '/') + "/DebugBuild.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package $pkg
            |
            |/** Baked at build time from -Pymusic.debugLogs (default true; false under -Pproduction). */
            |internal const val DEBUG_BUILD: Boolean = ${debugBuild.get()}
            |
            """.trimMargin(),
        )
    }
}

private fun Project.debugBuildFlagTask(packageName: String): TaskProvider<GenerateDebugBuildFlag> {
    val existing = tasks.findByName("generateDebugBuildFlag")
    if (existing != null) return tasks.named("generateDebugBuildFlag", GenerateDebugBuildFlag::class.java)

    val production = isProductionBuild()
    val debugBuild = providers.gradleProperty("ymusic.debugLogs").map(String::toBoolean).orElse(true)
        .map { it && !production }
    return tasks.register<GenerateDebugBuildFlag>("generateDebugBuildFlag") {
        this.debugBuild.set(debugBuild)
        this.packageName.set(packageName)
        outputDir.set(layout.buildDirectory.dir("generated/debugBuild"))
    }
}

private fun Project.inferDebugBuildPackage(): String? {
    val roots = listOf(
        "src/commonMain/kotlin",
        "src/jvmMain/kotlin",
        "src/iosMain/kotlin",
        "src/desktopMain/kotlin",
        "src/main/kotlin",
    ).map { file(it) }.filter { it.isDirectory }
    val packages = roots.flatMap { root ->
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { kt ->
                kt.useLines { lines ->
                    val line = lines.firstOrNull { it.startsWith("package ") } ?: return@useLines null
                    PACKAGE_NAME.find(line)?.groupValues?.get(1)
                }
            }
    }
    return longestCommonPackage(packages)
}

private val PACKAGE_NAME = Regex("""^package\s+([A-Za-z_][A-Za-z0-9_.]*)""")

private fun longestCommonPackage(packages: List<String>): String? {
    if (packages.isEmpty()) return null
    val split = packages.map { it.split('.') }
    val prefix = mutableListOf<String>()
    var i = 0
    while (true) {
        val seg = split[0].getOrNull(i) ?: break
        if (split.any { it.size <= i || it[i] != seg }) break
        prefix.add(seg)
        i++
    }
    return prefix.takeIf { it.isNotEmpty() }?.joinToString(".")
}
