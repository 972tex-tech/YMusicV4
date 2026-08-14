# Shrink-only pass (obfuscation + optimization OFF in the compose config): the goal is dead-code
# removal — material-icons-extended above all. Everything kept below is reachable only through
# reflection, JNI, or ServiceLoader — paths the shrinker cannot trace.

# QuickJS JNI bridge (same rule as androidApp): quickjs_jni.c resolves the class and these members
# BY NAME (FindClass / GetStaticMethodID / the exception ctor).
-keep class io.ymusic.quickjs.QuickJsNative { *; }
-keep class io.ymusic.quickjs.QuickJsException { <init>(java.lang.String); }

# kotlinx.serialization: plugin-generated serializers are looked up via the Companion (mirror of
# the androidApp rules).
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$Companion Companion;
}
-keep,allowshrinking,allowobfuscation class <2>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# JCEF: glued to native CEF by JNI in both directions — N_* natives resolved by name, and native
# code calls back into org.cef classes/methods the shrinker sees as unused. ~1MB, keep whole.
-keep class org.cef.** { *; }
-keep class me.friwi.jcefmaven.** { *; }

# Skiko (Compose's renderer): JNI in both directions, same story as JCEF.
-keep class org.jetbrains.skia.** { *; }
-keep class org.jetbrains.skiko.** { *; }

# JNA maps interface methods and Structure fields reflectively.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }
# Our direct JNA bridges (MacNowPlaying / MacDock map ObjC runtime functions by name).
-keep class io.ymusic.desktop.media.** { *; }

# vlcj: the libvlc API surface is JNA-mapped.
-keep class uk.co.caprica.vlcj.** { *; }

# sqlite-jdbc: DriverManager finds the driver via ServiceLoader; natives bind by name.
-keep class org.sqlite.** { *; }

# Sentry wires integrations reflectively (options/integration discovery).
-keep class io.sentry.** { *; }

# Coroutines: Dispatchers.Main is a ServiceLoader lookup (the Swing factory on desktop) — an
# impl class with no static reference. Same for exception-handler services.
-keep class * implements kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class * implements kotlinx.coroutines.CoroutineExceptionHandler { *; }

# Sentry events must map back to source lines.
-keepattributes SourceFile,LineNumberTable

# Library-internal references to optional/absent classes — not on our classpath, never hit:
# jogl's SWT/JavaFX/OpenAL canvases, commons-compress's zstd/xz/pack200 codecs (jcefmaven's
# extractor uses none of them for the CEF tarball), sqlite's & compress's slf4j hooks.
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
