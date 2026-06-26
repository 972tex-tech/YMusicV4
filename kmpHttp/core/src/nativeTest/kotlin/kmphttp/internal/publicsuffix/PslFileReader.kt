package kmphttp.internal.publicsuffix

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen

@OptIn(ExperimentalForeignApi::class)
internal fun readPslFile(): String {
    // Try multiple possible locations for the PSL file
    val possiblePaths = listOf(
        "src/nativeTest/resources/public_suffix_list.dat",
        "../src/nativeTest/resources/public_suffix_list.dat",
        "kmpHttp/core/src/nativeTest/resources/public_suffix_list.dat",
        "../kmpHttp/core/src/nativeTest/resources/public_suffix_list.dat",
        "../../kmpHttp/core/src/nativeTest/resources/public_suffix_list.dat",
    )

    for (path in possiblePaths) {
        val content = tryReadFile(path)
        if (content.isNotEmpty()) {
            println("Read PSL from: $path")
            return content
        }
    }

    println("Could not find PSL file in any of: $possiblePaths")
    return ""
}

@OptIn(ExperimentalForeignApi::class)
private fun tryReadFile(path: String): String {
    val file = fopen(path, "r") ?: return ""
    val content = StringBuilder()
    val buffer = ByteArray(4096)

    try {
        while (true) {
            val line = fgets(buffer.refTo(0), buffer.size, file) ?: break
            content.append(line.toKString())
        }
    } finally {
        fclose(file)
    }

    return content.toString()
}
