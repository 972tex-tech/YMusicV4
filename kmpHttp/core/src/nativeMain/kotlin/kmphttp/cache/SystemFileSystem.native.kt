package kmphttp.cache

import kmphttp.AsyncSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import okio.Buffer
import okio.FileSystem.Companion.SYSTEM
import okio.Path
import okio.buffer

actual object SystemFileSystem : FileSystem {
    actual override fun deleteRecursively(fileOrDirectory: Path) = SYSTEM.deleteRecursively(fileOrDirectory)
    actual override fun exists(path: Path): Boolean = SYSTEM.exists(path)
    actual override fun atomicMove(source: Path, dest: Path) = SYSTEM.atomicMove(source, dest)
    actual override fun listFile(dir: Path): Deferred<List<Path>> = CompletableDeferred(SYSTEM.list(dir))
    actual override fun mkdirs(path: Path) = SYSTEM.createDirectories(path)
    actual override fun isDirectory(path: Path): Boolean = SYSTEM.metadataOrNull(path)?.isDirectory == true
    actual override fun fileLength(path: Path): Long? = SYSTEM.metadataOrNull(path)?.size
    actual override suspend fun asyncSink(path: Path, append: Boolean): AsyncSink {
        val sink = (if (append) SYSTEM.appendingSink(path) else SYSTEM.sink(path)).buffer()
        return object : AsyncSink {
            override suspend fun write(buffer: Buffer, byteCount: Long) {
                sink.write(buffer, byteCount)
            }

            override suspend fun flush() {
                sink.flush()
            }

            override fun close() {
                sink.close()
            }
        }
    }
    actual override suspend fun asyncSource(path: Path): AsyncSource {
        val source = SYSTEM.source(path).buffer()
        return object : AsyncSource {
            override suspend fun read(sink: Buffer, byteCount: Long): Long {
                return source.read(sink, byteCount)
            }

            override fun close() {
                source.close()
            }
        }
    }
}