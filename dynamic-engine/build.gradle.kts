import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    // AGP KMP plugin (same as :media-store / :kmpFfmpeg) — supplies the `android {}` KMP target.
    id("com.android.kotlin.multiplatform.library")
    // SQLDelight codegen — the cache store indexes its metadata in SQLite (blobs stay on the FS).
    id("app.cash.sqldelight")
}

sqldelight {
    databases {
        create("CacheDatabase") {
            packageName.set("ymusic.dynamicengine.db")
            // .sq lives in src/commonMain/sqldelight — one shared schema for every platform.
        }
    }
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
                // api: SqliteCacheStore's public constructor takes okio FileSystem/Path (blob dir)
                // and a SqlDelight SqlDriver (the metadata index).
                api(Libs.Okio.core)
                api(Libs.SqlDelight.runtime)
                implementation(project(":kmpHttp:json"))
                implementation(Libs.Kotlinx.coroutinesCore)
            }
        }

        // Platform SqlDelight drivers for the DiskCacheStore factories (jvm = JDBC, android = the
        // AndroidSqliteDriver). Native shells don't wire the cache yet, so no nativeMain driver.
        jvmMain.dependencies {
            implementation(Libs.SqlDelight.sqliteDriver)
        }
        androidMain.dependencies {
            implementation(Libs.SqlDelight.androidDriver)
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(Libs.Kotlinx.coroutinesTest)
            }
        }

        // In-memory SQLite drivers for the cache-store tests (JVM = JDBC, native = sqliter).
        jvmTest.dependencies {
            implementation(Libs.SqlDelight.sqliteDriver)
        }
        val nativeTest by getting {
            dependencies {
                implementation(Libs.SqlDelight.nativeDriver)
            }
        }
    }
}

// The :engine-js bundles, embedded as consts: ENGINE_JS (production, Closure ADVANCED) is the
// baseline DynamicEngine.create evals as the runtime's creation script (a remote bundle replaces
// it via the engineJs parameter); ENGINE_TEST_JS (production + test fixtures) is what the tests
// run on.
fun embedBundle(configName: String, projectConfig: String, constName: String, ktFile: String, sourceSet: String) {
    val config = configurations.create(configName) {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
    dependencies { config(project(":engine-js", projectConfig)) }
    val ktDir = layout.buildDirectory.dir("generated/" + configName)
    val taskName = "generate" + configName.replaceFirstChar { it.uppercase() } + "Kt"
    val task = tasks.register(taskName) {
        inputs.files(config)
        outputs.dir(ktDir)
        doLast {
            val bundle = config.singleFile.readText()
            check(bundle.isNotBlank()) { "engine-js produced an empty bundle for " + configName }
            val escaped = bundle
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("${'$'}", "${'$'}{'${'$'}'}").replace("\n", "\\n").replace("\t", "\\t")
            val dir = ktDir.get().asFile.resolve("ymusic/dynamicengine/internal").apply { mkdirs() }
            dir.resolve(ktFile).writeText(
                """
                |package ymusic.dynamicengine.internal
                |
                |// GENERATED — do not edit. The :engine-js "$projectConfig" artifact, embedded at build time.
                |internal const val $constName: String = "$escaped"
                |""".trimMargin(),
            )
        }
    }
    kotlin.sourceSets.getByName(sourceSet).kotlin.srcDir(files(ktDir).builtBy(task))
}

embedBundle("engineJsBundle", "engineJs", "ENGINE_JS", "EngineJsPreamble.kt", "commonMain")
embedBundle("engineTestJsBundle", "engineTestJs", "ENGINE_TEST_JS", "EngineTestJsPreamble.kt", "commonTest")
