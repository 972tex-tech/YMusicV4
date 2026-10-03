/*
 * Copyright (C) 2025 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kmphttp.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.usePinned
import okio.Buffer
import platform.Foundation.NSData
import platform.posix.memcpy
import platform.posix.uint8_tVar

/**
 * Writes NSData directly to this Buffer without creating an intermediate ByteArray.
 * Uses UnsafeCursor to allocate space once, then write to segments directly.
 *
 * @param data the NSData to write
 * @return number of bytes written
 */
@OptIn(ExperimentalForeignApi::class)
internal fun Buffer.write(data: NSData): Long {
    val length = data.length.toLong()
    if (length == 0L) return 0L

    val sourcePtr = data.bytes ?: return 0L

    val cursor = Buffer.UnsafeCursor()
    readAndWriteUnsafe(cursor)
    try {
        // Allocate all space at once, cursor positioned at start of new space
        cursor.resizeBuffer(size + length)

        var remaining = length
        var sourceOffset = 0L

        // Write to each segment
        do {
            val segmentData = cursor.data ?: break
            val available = cursor.end - cursor.start
            val toWrite = minOf(available.toLong(), remaining)

            segmentData.usePinned { pinned ->
                val srcPtr = interpretCPointer<uint8_tVar>(sourcePtr.rawValue + sourceOffset)
                memcpy(pinned.addressOf(cursor.start), srcPtr, toWrite.toULong())
            }

            sourceOffset += toWrite
            remaining -= toWrite
        } while (remaining > 0 && cursor.next() != -1)

        check(remaining == 0L)
        return length
    } finally {
        cursor.close()
    }
}
