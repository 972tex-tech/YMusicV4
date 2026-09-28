package io.ymusic.mse

import androidx.annotation.Keep
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

interface MseHostSink {
    @Keep fun onFrame(width: Int, height: Int, bgra: ByteBuffer)

    @Keep fun onPcm(rate: Int, channels: Int, s16: ByteArray)

    @Keep fun onProgress(positionSec: Double, durationSec: Double, width: Int, height: Int)
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

    @JvmStatic
    actual external fun setVolume(handle: Long, vol: Float)

    @JvmStatic
    actual external fun setSpeed(handle: Long, speed: Float)

    @JvmStatic
    actual external fun setPitch(handle: Long, pitch: Float)

    @JvmStatic
    actual external fun setEq(handle: Long, bands: FloatArray)

    @JvmStatic
    actual external fun setBass(handle: Long, db: Float)

    @JvmStatic
    actual external fun setVirtualizer(handle: Long, width: Float)

    /** macOS: overlay VideoToolbox on [component] via JAWT. Other JVMs return false. */
    @JvmStatic
    external fun setVideoComponent(handle: Long, component: java.awt.Component?): Boolean

    private fun loadLibrary() {
        if (!loaded.compareAndSet(false, true)) return
        runCatching { System.loadLibrary("jawt") }
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
        // Unpack JNI + sibling libav into one temp dir so @loader_path / $ORIGIN resolves.
        val folder = "jni/${hostJniFolder()}"
        val cl = MseHostNative::class.java.classLoader
            ?: throw UnsatisfiedLinkError("No classloader for $name")
        val names = cl.getResourceAsStream("$folder/natives.txt")?.bufferedReader()?.use { it.readLines() }
            ?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: listOf(libFileName)
        val tmpDir = Files.createTempDirectory("mseffmpegjni").toFile().also { it.deleteOnExit() }
        for (n in names) {
            val stream = cl.getResourceAsStream("$folder/$n")
                ?: throw UnsatisfiedLinkError("No bundled $name at $folder/$n")
            stream.use { inp ->
                val dest = File(tmpDir, n).also { it.deleteOnExit() }
                Files.copy(inp, dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        System.load(File(tmpDir, libFileName).absolutePath)
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
