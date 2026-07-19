package kmphttp.cache

import kmpcommon.io.AsyncSink
import kmpcommon.io.AsyncSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import okio.Buffer
import okio.Path
import okio.buffer

/**
 * subset functions of Okio's [okio.FileSystem].
 * This has two special functions [asyncSink] and [asyncSource], which only truly async on NodeJs
 * to avoid blocking its main event loop, other platform is just a wrapper of [okio.FileSystem.sink]
 * and [okio.FileSystem.source].
 */
interface FileSystem {
    fun deleteRecursively(fileOrDirectory: Path)
    fun exists(path: Path): Boolean
    fun atomicMove(source: Path, dest: Path)
    fun listFile(dir: Path): Deferred<List<Path>>
    fun mkdirs(path: Path)
    fun isDirectory(path: Path): Boolean
    fun fileLength(path: Path): Long?
    suspend fun asyncSink(path: Path, append: Boolean): AsyncSink
    suspend fun asyncSource(path: Path): AsyncSource
}

/** [FileSystem] over any okio filesystem (blocking ops wrapped; see the interface note). */
class OkioFileSystem(private val delegate: okio.FileSystem) : FileSystem {
    override fun deleteRecursively(fileOrDirectory: Path) = delegate.deleteRecursively(fileOrDirectory)
    override fun exists(path: Path): Boolean = delegate.exists(path)
    override fun atomicMove(source: Path, dest: Path) = delegate.atomicMove(source, dest)
    override fun listFile(dir: Path): Deferred<List<Path>> = CompletableDeferred(delegate.list(dir))
    override fun mkdirs(path: Path) = delegate.createDirectories(path)
    override fun isDirectory(path: Path): Boolean = delegate.metadataOrNull(path)?.isDirectory == true
    override fun fileLength(path: Path): Long? = delegate.metadataOrNull(path)?.size
    override suspend fun asyncSink(path: Path, append: Boolean): AsyncSink {
        val sink = (if (append) delegate.appendingSink(path) else delegate.sink(path)).buffer()
        return object : AsyncSink {
            override suspend fun write(buffer: Buffer, byteCount: Long) = sink.write(buffer, byteCount)
            override suspend fun flush() = sink.flush()
            override fun close() = sink.close()
        }
    }
    override suspend fun asyncSource(path: Path): AsyncSource {
        val source = delegate.source(path).buffer()
        return object : AsyncSource {
            override suspend fun read(sink: Buffer, byteCount: Long): Long = source.read(sink, byteCount)
            override fun close() = source.close()
        }
    }
}

expect object SystemFileSystem : FileSystem {
    override fun deleteRecursively(fileOrDirectory: Path)
    override fun exists(path: Path): Boolean
    override fun atomicMove(source: Path, dest: Path)
    override fun listFile(dir: Path): Deferred<List<Path>>
    override fun mkdirs(path: Path)
    override fun isDirectory(path: Path): Boolean
    override fun fileLength(path: Path): Long?
    override suspend fun asyncSink(path: Path, append: Boolean): AsyncSink
    override suspend fun asyncSource(path: Path): AsyncSource
}
