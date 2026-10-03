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

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.usePinned
import okio.Buffer
import platform.posix.memcpy
import platform.posix.uint8_tVar

/**
 * Copies bytes from this Buffer directly to a CPointer without creating intermediate ByteArray.
 * Uses UnsafeCursor to access Buffer's internal segments directly.
 *
 * @param dest destination CPointer to copy bytes to
 * @param byteCount maximum number of bytes to copy
 * @return actual number of bytes copied (may be less than byteCount if buffer has fewer bytes)
 */
@OptIn(ExperimentalForeignApi::class)
internal fun Buffer.read(dest: CPointer<uint8_tVar>, byteCount: Long): Long {
    if (size == 0L || byteCount <= 0L) return 0L

    val toRead = minOf(size, byteCount)
    var totalCopied = 0L

    val cursor = Buffer.UnsafeCursor()
    readUnsafe(cursor)
    try {
        var remaining = toRead
        var destOffset = 0L

        while (remaining > 0 && cursor.next() != -1) {
            val segmentData = cursor.data ?: break
            val available = cursor.end - cursor.start
            val toCopy = minOf(available.toLong(), remaining)

            segmentData.usePinned { pinned ->
                val destPtr = interpretCPointer<uint8_tVar>(dest.rawValue + destOffset)
                memcpy(destPtr, pinned.addressOf(cursor.start), toCopy.toULong())
            }

            destOffset += toCopy
            remaining -= toCopy
            totalCopied += toCopy
        }
    } finally {
        cursor.close()
    }
    skip(totalCopied)
    return totalCopied
}
