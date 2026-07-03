package ymusic.quickjs

import java.io.File

/** JVM: load from `quickjs.native.dir` (set by the Gradle test task) or the java.library.path. */
internal actual fun loadQuickJsLibrary() {
    val dir = System.getProperty("quickjs.native.dir")
    val libName = System.mapLibraryName("quickjsjni")
    if (dir != null) System.load(File(dir, libName).absolutePath) else System.loadLibrary("quickjsjni")
}
