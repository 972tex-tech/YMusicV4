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

//include(":common")
//include(":commonUi")
//include(":androidApp")
include(":kmpCommon")
include(":kmpHttp:core")
include(":kmpHttp:json")
include(":kmpHttp:json-benchmark")
include(":kmpHttp:curl")
include(":kmpFfmpeg")
include(":media-store")
//includeBuild("YMusicLegacy")
