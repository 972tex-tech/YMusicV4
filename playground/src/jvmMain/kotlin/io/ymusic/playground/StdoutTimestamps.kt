package io.ymusic.playground

import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

/** Prefix each stdout/stderr line with local time at millisecond precision. */
private class TimestampOutputStream(out: OutputStream) : FilterOutputStream(out) {
    private val line = ByteArrayOutputStream()

    @Synchronized
    override fun write(b: Int) {
        if (b == '\n'.code) {
            flushLine()
        } else if (b != '\r'.code) {
            line.write(b)
        }
    }

    @Synchronized
    override fun write(buf: ByteArray, off: Int, len: Int) {
        val end = off + len
        for (i in off until end) {
            write(buf[i].toInt() and 0xff)
        }
    }

    private fun flushLine() {
        val prefix = LocalTime.now().format(TIME_FMT) + " "
        out.write(prefix.toByteArray(Charsets.UTF_8))
        line.writeTo(out)
        out.write('\n'.code)
        out.flush()
        line.reset()
    }
}

internal fun installStdoutTimestamps() {
    System.setOut(PrintStream(TimestampOutputStream(FileOutputStream(FileDescriptor.out)), true))
    System.setErr(PrintStream(TimestampOutputStream(FileOutputStream(FileDescriptor.err)), true))
}
