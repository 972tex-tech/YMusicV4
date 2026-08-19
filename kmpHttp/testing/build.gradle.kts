import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Test-support for modules faking a KmpHttpClient: InMemoryCookieJar lives here so it is not
// part of :kmpHttp:core's public surface. Production jars are the WebView-backed ones (or
// NO_COOKIES); this one exists for tests that need real cookie semantics without a browser.
plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // The full apple set, matching :kmpHttp:core — its commonTest depends on this module, so
    // every target core compiles tests for must exist here too (watchos/tvos included).
    withAppleTargets()
    withLinuxTargets()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":kmpHttp:core")) // CookieJar, Cookie, HttpUrl surface in the class API
            implementation(Libs.Kotlinx.coroutinesCore) // the native actual locks with a Mutex
        }
    }
}
