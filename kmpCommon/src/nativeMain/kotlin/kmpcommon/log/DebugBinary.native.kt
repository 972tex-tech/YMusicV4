@file:OptIn(ExperimentalNativeApi::class)

package kmpcommon.log

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

// A compile-time constant of the FINAL binary compilation (klibs ship IR; codegen happens when the
// framework/binary is built): false in release binaries, so the inlined check folds and the debug
// sites are dropped from the machine code.
@PublishedApi
internal actual val debugBinary: Boolean get() = Platform.isDebugBinary
