# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Strip Logger.debug {} call sites from release builds: pin the flag false so R8 constant-folds
# the inlined check and removes the message-building code entirely (takes effect once
# isMinifyEnabled = true on the release build type).
-assumevalues class kmpcommon.log.LogConfig {
    boolean getDebugLogs() return false;
    boolean debugLogs return false;
}
