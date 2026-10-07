@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "YMusicV4"

include(":commonUi")
include(":image-loader")
include(":desktopApp")
include(":androidApp")
include(":kmpCommon")
include(":projectCommon")
include(":kmpHttp:core")
include(":kmpHttp:json")
include(":kmpHttp:json-benchmark")
include(":kmpHttp:curl")
include(":kmpHttp:testing")
include(":kmpFfmpeg")
include(":media-store")
include(":media-scanner")
include(":dynamic-engine")
include(":engine-js")
include(":quickjs")
include(":player")
include(":mse")
include(":yt-media")
include(":download")
include(":iosAppKotlin")
include(":playground")
include(":playground-android")
include(":settings")
include(":remote-config")
include(":telemetry")
// includeBuild("YMusicLegacy")
