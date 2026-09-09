package io.ymusic.mse

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

interface MseHostSink {
    fun onFrame(width: Int, height: Int, bgra: ByteBuffer)
    fun onPcm(rate: Int, channels: Int, s16: ByteArray)
    fun onProgress(positionSec: Double, durationSec: Double, width: Int, height: Int)
}

actual object MseHostNative {
    private val loaded = AtomicBoolean(false)

    init {
        loadLibrary()
    }

    @JvmStatic
    actual external fun vtable(): Long

    @JvmStatic
    actual external fun create(): Long

    @JvmStatic
    actual external fun destroy(handle: Long)

    @JvmStatic
    actual external fun frames(handle: Long): Int

    @JvmStatic
    actual external fun samplesPlayed(handle: Long): Long

    actual fun copyFrame(handle: Long, afterGen: Int): NativeMseFrame? {
        if (handle == 0L) return null
        val meta = IntArray(3)
        val bgra = copyFrameBytes(handle, afterGen, meta) ?: return null
        return NativeMseFrame(meta[0], meta[1], meta[2], bgra)
    }

    @JvmStatic
    private external fun copyFrameBytes(handle: Long, afterGen: Int, meta: IntArray): ByteArray?

    @JvmStatic
    external fun setSink(handle: Long, sink: MseHostSink)

    @JvmStatic
    external fun videoSize(handle: Long, outWh: IntArray): Boolean

    /** 0×0 = decode size. Downscale-only letterbox fit when both > 0. */
    @JvmStatic
    external fun setBlitSize(handle: Long, width: Int, height: Int)

    /** macOS: overlay VideoToolbox on [component] via JAWT. Other JVMs return false. */
    @JvmStatic
    external fun setVideoComponent(handle: Long, component: java.awt.Component?): Boolean

    private fun loadLibrary() {
        if (!loaded.compareAndSet(false, true)) return
        val name = "mseffmpegjni"
        val libFileName = System.mapLibraryName(name)
        System.getProperty("mse.native.dir")?.let { dir ->
            System.load(File(dir, libFileName).absolutePath)
            return
        }
        try {
            System.loadLibrary(name)
            return
        } catch (_: UnsatisfiedLinkError) {
        }
        val resource = "jni/${hostJniFolder()}/$libFileName"
        val stream = MseHostNative::class.java.classLoader?.getResourceAsStream(resource)
            ?: throw UnsatisfiedLinkError("No bundled $name at $resource")
        stream.use { inp ->
            val suffix = "." + libFileName.substringAfterLast('.', "dylib")
            val tmp = Files.createTempFile("mseffmpegjni", suffix).also { it.toFile().deleteOnExit() }
            Files.copy(inp, tmp, StandardCopyOption.REPLACE_EXISTING)
            System.load(tmp.toAbsolutePath().toString())
        }
    }

    private fun hostJniFolder(): String {
        val os = System.getProperty("os.name").lowercase(Locale.US)
        val arch = System.getProperty("os.arch").lowercase(Locale.US)
        val osPrefix = when {
            os.contains("mac") || os.contains("osx") -> "macos"
            os.contains("linux") -> "linux"
            os.contains("win") -> "windows"
            else -> error("Unsupported OS: $os")
        }
        val archSuffix = when (arch) {
            "aarch64" -> "aarch64"
            "amd64", "x86_64" -> "x64"
            else -> error("Unsupported arch: $arch")
        }
        return "${osPrefix}_$archSuffix"
    }
}
