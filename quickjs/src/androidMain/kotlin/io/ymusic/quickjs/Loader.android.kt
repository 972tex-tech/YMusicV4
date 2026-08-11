package io.ymusic.quickjs

/** Android: the .so ships in the APK's jniLibs and loads by name. */
internal actual fun loadQuickJsLibrary() {
    System.loadLibrary("quickjsjni")
}
