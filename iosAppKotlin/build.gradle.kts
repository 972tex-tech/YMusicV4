plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * The iOS product shell over :commonUi — the composition-root peer of :androidApp / :desktopApp.
 * Builds the AppGraph (engine manager, player facade, cookie jar, settings) and produces the
 * `YMusicApp` framework the Xcode project embeds; `MainViewController()` is the Swift entry point.
 */
kotlin {
    withComposeIosTargets {
        binaries.framework {
            baseName = "YMusicApp"
            isStatic = false
            // Compose MP 1.11.1's ui-uikit links iOS 26 symbols (UIViewLayoutRegion), which need the
            // iOS 26 SDK / Xcode 26. To build on an older Xcode, allow those undefined at link — Compose
            // @available-guards their use, so they're never touched below iOS 26. Drop when on Xcode 26.
            if (providers.gradleProperty("iosAllowUndefined").orNull == "true") {
                linkerOpts("-undefined", "dynamic_lookup")
            }
        }
    }
    // The KMP plugin requires at least one target, and the Compose iOS targets are host-gated OFF
    // in this Intel host's default mode — a sourceless jvm() keeps the module configurable there.
    // Real builds pass -PkmpTargets=all (the Xcode build phase does).
    if (targets.none { it is org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget }) jvm()

    // Lazy: iosMain only exists once the (host-gated) ios targets are configured.
    sourceSets.matching { it.name == "iosMain" }.configureEach {
        dependencies {
            api(project(":commonUi"))
            implementation(project(":yt-media")) // the player-cache router (LocalMediaProxy) for the WebView
            implementation(project(":kmpHttp:core"))
            implementation(project(":kmpCommon"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
    }
}
