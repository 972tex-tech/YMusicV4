# Shrink-only pass (obfuscation + optimization OFF in the compose config): dead-code removal is
# the point — material-icons-extended above all. Everything kept below is reachable only through
# reflection, JNI, or ServiceLoader — paths the shrinker cannot trace.
#
# The compose plugin ALWAYS prepends its bundled default-compose-desktop-rules.pro: kotlin.** and
# skia/skiko keep-alls, the upstream kotlinx-coroutines rules (volatile AFU fields, Swing
# MainDispatcherFactory), and the full kotlinx-serialization ruleset. None of that is repeated
# here — this file holds only what the defaults don't cover.

# JNI floor: a class declaring native methods keeps them (binding is by declaration — Native
# .register / RegisterNatives walk the declared methods).
-keepclasseswithmembers,includedescriptorclasses class * { native <methods>; }

# QuickJS JNI bridge (same rule as androidApp): quickjs_jni.c resolves the class and these members
# BY NAME (FindClass / GetStaticMethodID / the exception ctor), including non-native callback
# methods invoked from C — the JNI floor above doesn't cover those.
-keep class io.ymusic.quickjs.QuickJsNative { *; }
-keep class io.ymusic.quickjs.QuickJsException { <init>(java.lang.String); }

# JCEF: two-way by-name JNI with no official shrink ruleset anywhere (JetBrains ship jcef in the
# JBR unshrunk) — native CEF FindClass-es and constructs org.cef callback classes (*_N) that no
# Java code references, and invokes handler methods directly. Deliberately kept whole: the entire
# jcef-api class payload is ~1MB pre-compression; not worth the breakage surface.
# (jcefmaven itself is plain statically-reachable Java — no keep.)
-keep class org.cef.** { *; }

# JNA core (the artifact's own packages, per JNA's documented rule): Structure fields, Library
# interface methods, and Callback discovery are all resolved reflectively by the native dispatcher.
# Deliberately NOT com.sun.jna.** — that would also pin com.sun.jna.platform.** (jna-platform),
# whose unused mappings can shrink.
-keep class com.sun.jna.* { *; }
-keep class com.sun.jna.internal.* { *; }
-keep class com.sun.jna.ptr.* { *; }
-keep class com.sun.jna.win32.* { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }

# vlcj: only its JNA-facing layer needs pinning — libvlc_* structures and the direct-mapped
# binding surface live in binding.**; the high-level API above it is statically reachable and
# free to shrink. (Callbacks/Structures are additionally covered by the JNA rules above.)
-keep class uk.co.caprica.vlcj.binding.** { *; }

# sqlite-jdbc: the JDBC driver is found by name (META-INF/services/java.sql.Driver +
# DriverManager), and the native library resolves a fixed set of classes AND methods by name AT
# LIBRARY-LOAD TIME — across org.sqlite.core (NativeDB, DB$ProgressObserver.progress, …) and the
# org.sqlite callback types (Function$Aggregate, …) — so System.load fails if any is shrunk away,
# even when custom functions are never used (both measured, 2026-08-14). The jdbc3/jdbc4 layers
# are statically reachable — they shrink.
-keep class org.sqlite.JDBC { *; }
-keep class org.sqlite.core.** { *; }
-keep class org.sqlite.Function { *; }
-keep class org.sqlite.Function$* { *; }
-keep class org.sqlite.ProgressHandler { *; }
-keep class org.sqlite.BusyHandler { *; }
-keep class org.sqlite.Collation { *; }

# Sentry (JVM flavor): no keeps needed — protocol/envelope JSON is hand-rolled (JsonSerializable,
# no reflection over model classes), and its Class.forName probes for optional integrations
# (spring/servlet/otel) just report "absent" when shrunk away. sentry-android's consumer rules
# exist for Android-specific reflection this JVM shell never touches.

# Sentry events must map back to source lines.
-keepattributes SourceFile,LineNumberTable

# Library-internal references to optional/absent classes — not on our classpath, never hit:
# jogl's SWT/JavaFX/OpenAL canvases, commons-compress's zstd/xz/pack200 codecs (jcefmaven's
# extractor uses none of them for the CEF tarball), sqlite's slf4j hook, okhttp's GraalVM shims.
-dontwarn com.jogamp.**
-dontwarn jogamp.**
# jogl/gluegen are excluded from the classpath entirely (build.gradle.kts) — the kept-but-never-
# instantiated CefBrowserOsr/CefGLCanvas still reference them.
-dontwarn org.cef.browser.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.sqlite.util.**
-dontwarn okhttp3.internal.platform.**
-dontwarn okhttp3.internal.graal.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontnote **
