package kmphttp.cache

import kmpcommon.io.AsyncSink
import kmpcommon.io.AsyncSource
import kotlinx.coroutines.Deferred
import okio.Path

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
