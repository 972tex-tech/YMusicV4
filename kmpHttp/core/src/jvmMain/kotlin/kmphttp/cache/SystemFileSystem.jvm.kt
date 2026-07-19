package kmphttp.cache

import kmpcommon.io.AsyncSink
import kmpcommon.io.AsyncSource
import kotlinx.coroutines.Deferred
import okio.FileSystem.Companion.SYSTEM
import okio.Path

private val system = OkioFileSystem(SYSTEM)

actual object SystemFileSystem : FileSystem {
    actual override fun deleteRecursively(fileOrDirectory: Path) = system.deleteRecursively(fileOrDirectory)
    actual override fun exists(path: Path): Boolean = system.exists(path)
    actual override fun atomicMove(source: Path, dest: Path) = system.atomicMove(source, dest)
    actual override fun listFile(dir: Path): Deferred<List<Path>> = system.listFile(dir)
    actual override fun mkdirs(path: Path) = system.mkdirs(path)
    actual override fun isDirectory(path: Path): Boolean = system.isDirectory(path)
    actual override fun fileLength(path: Path): Long? = system.fileLength(path)
    actual override suspend fun asyncSink(path: Path, append: Boolean): AsyncSink = system.asyncSink(path, append)
    actual override suspend fun asyncSource(path: Path): AsyncSource = system.asyncSource(path)
}
