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
include(":kmpHttp:core")
include(":kmpHttp:json")
include(":kmpHttp:json-benchmark")
include(":kmpHttp:curl")
include(":kmpFfmpeg")
include(":media-store")
include(":media-scanner")
include(":dynamic-engine")
include(":engine-js")
include(":quickjs")
include(":player")
include(":playground")
include(":settings")
// includeBuild("YMusicLegacy")
