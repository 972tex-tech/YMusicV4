@file:JvmName("DebugBinary")

package kmpcommon.log

// The JVM has no debug/release binary notion of its own: true here, pinned false by R8's
// -assumevalues rule on minified Android release builds (androidApp/proguard-rules.pro).
@PublishedApi
internal actual val debugBinary: Boolean get() = true
