/*
 * Copyright (C) 2019 Square, Inc.
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
package kmphttp

import kmphttp.MediaTypeX.toMediaType
import kmphttp.RequestBodyX.toRequestBody
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class RequestBodyTest {
    @Test
    fun stringRequestBody() {
        val body = "Hello".toRequestBody()
        assertNull(body.contentType())
        assertEquals(5, body.contentLength())
        assertFalse(body.isOneShot())
    }

    @Test
    fun stringRequestBodyWithContentType() {
        val contentType = "text/plain".toMediaType()
        val body = "Hello".toRequestBody(contentType)
        assertEquals("text/plain; charset=utf-8".toMediaType(), body.contentType())
        assertEquals(5, body.contentLength())
    }

    @Test
    fun stringRequestBodyWithCharset() {
        val contentType = "text/plain; charset=utf-16be".toMediaType()
        val body = try {
            "Hello".toRequestBody(contentType)
        } catch (e: IllegalArgumentException) {
            // expect crash on non-jvm
            assertNotEquals(BUILD_TARGET, KmpBuildTarget.JVM)
            assertContains(e.message.orEmpty(), "Non-JVM platforms only support UTF-8 charset")
            return
        }
        assertEquals(contentType, body.contentType())
        assertEquals(10, body.contentLength()) // UTF-16BE: 2 bytes per char
    }

    @Test
    fun byteStringRequestBody() {
        val byteString = "Hello".encodeUtf8()
        val body = byteString.toRequestBody()
        assertNull(body.contentType())
        assertEquals(5, body.contentLength())
        assertFalse(body.isOneShot())
    }

    @Test
    fun byteStringRequestBodyWithContentType() {
        val contentType = "application/octet-stream".toMediaType()
        val byteString = "Hello".encodeUtf8()
        val body = byteString.toRequestBody(contentType)
        assertEquals(contentType, body.contentType())
        assertEquals(5, body.contentLength())
    }

    @Test
    fun byteArrayRequestBody() {
        val bytes = "Hello".encodeToByteArray()
        val body = bytes.toRequestBody()
        assertNull(body.contentType())
        assertEquals(5, body.contentLength())
        assertFalse(body.isOneShot())
    }

    @Test
    fun byteArrayRequestBodyWithContentType() {
        val contentType = "application/octet-stream".toMediaType()
        val bytes = "Hello".encodeToByteArray()
        val body = bytes.toRequestBody(contentType)
        assertEquals(contentType, body.contentType())
        assertEquals(5, body.contentLength())
    }

    @Test
    fun byteArrayRequestBodyWithOffsetAndLength() {
        val bytes = "Hello, World!".encodeToByteArray()
        val body = bytes.toRequestBody(null, 7, 5) // "World"
        assertNull(body.contentType())
        assertEquals(5, body.contentLength())
    }

    @Test
    fun emptyRequestBody() {
        val body = RequestBody.EMPTY
        assertNull(body.contentType())
        assertEquals(0, body.contentLength())
        assertFalse(body.isOneShot())
    }

    @Test
    fun emptyStringRequestBody() {
        val body = "".toRequestBody()
        assertNull(body.contentType())
        assertEquals(0, body.contentLength())
    }

    @Test
    fun unicodeStringRequestBody() {
        val text = "日本語"
        val body = text.toRequestBody()
        // UTF-8: 3 bytes per CJK character
        assertEquals(9, body.contentLength())
    }

    @Test
    fun emojiStringRequestBody() {
        val text = "😀"
        val body = text.toRequestBody()
        // UTF-8: 4 bytes for emoji
        assertEquals(4, body.contentLength())
    }
}
