package kmphttp

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform

// Runtime check: true on the macosX64/macosArm64 binaries, false on iOS/Linux.
@OptIn(ExperimentalNativeApi::class)
internal actual fun isMacosHost(): Boolean = Platform.osFamily == OsFamily.MACOSX
