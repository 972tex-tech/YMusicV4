import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    kotlin("plugin.allopen")
    id("org.jetbrains.kotlinx.benchmark")
}

allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    // Host-gated so the benchmark's macOS targets match the host-gated :kmpHttp:json it depends on.
    withMacosTargets()

    sourceSets {
        commonMain.dependencies {
            implementation(Libs.Kotlinx.coroutinesCore)
            implementation(Libs.Kotlinx.serializationJson)
            implementation(Libs.Kotlinx.serializationJsonOkio)
            implementation(Libs.Okio.core)
            implementation(Libs.Benchmark.runtime)
            implementation(project(":kmpHttp:json"))
            implementation(project(":kmpHttp:core"))
        }
        jvmMain.dependencies {
            implementation(Libs.Moshi.core)
            implementation(Libs.Moshi.kotlin)
        }
    }
}

benchmark {
    targets {
        register("jvm")
        // Register whichever macOS target the host configured (see withMacosTargets()).
        kotlin.targets.names.filter { it.startsWith("macos") }.forEach { register(it) }
    }
    configurations {
        named("main") {
            warmups = 5
            iterations = 5
            iterationTime = 3000
            iterationTimeUnit = "ms"
        }
    }
}

// JITWatch benchmark task - generates JIT compilation logs for analysis
// Run with: ./gradlew :kmpHttp:json-benchmark:jvmJitwatchBenchmark
// Then open kmpHttp/json-benchmark/build/jitwatch/hotspot.log in JITWatch
tasks.register<JavaExec>("jvmJitwatchBenchmark") {
    group = "benchmark"
    description = "Run JVM benchmarks with JIT logging for JITWatch analysis"

    dependsOn("jvmBenchmarkJar")

    val benchmarkJar = layout.buildDirectory.file("benchmarks/jvm/jars/json-benchmark-jvm-jmh-JMH.jar")
    val logDir = layout.buildDirectory.dir("jitwatch")
    val logFile = logDir.get().file("hotspot.log").asFile

    doFirst {
        logDir.get().asFile.mkdirs()
    }

    mainClass.set("org.openjdk.jmh.Main")
    classpath = files(benchmarkJar)

    jvmArgs = listOf(
        "-XX:+UnlockDiagnosticVMOptions",
        "-XX:+LogCompilation",
        "-XX:LogFile=${logFile.absolutePath}",
        "-XX:+TraceClassLoading"
    )

    // JMH args: 3 warmups, 3 iterations, 1 fork, run only largeKeyValue benchmark
    args = listOf("-wi", "3", "-i", "3", "-f", "1", ".*largeKeyValue.*")
}
