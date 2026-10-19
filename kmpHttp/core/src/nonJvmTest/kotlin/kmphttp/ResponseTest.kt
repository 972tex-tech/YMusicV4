/*
 * Copyright (C) 2016 Square, Inc.
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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResponseTest {
    @Test
    fun negativeStatusCodeThrowsIllegalStateException() {
        assertFailsWith<IllegalStateException> {
            newResponse(code = -1)
        }
    }

    @Test
    fun zeroStatusCodeIsValid() {
        val response = newResponse(code = 0)
        assertEquals(0, response.code)
    }

    @Test
    fun defaultResponseBodyIsEmpty() {
        val response = ResponseBuilder()
            .request(
                RequestBuilder()
                    .url("https://example.com/")
                    .build(),
            )
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .build()
        assertNull(response.body.contentType())
        assertEquals(0L, response.body.contentLength())
    }

    @Test
    fun responseCode() {
        val response = newResponse(code = 200)
        assertEquals(200, response.code)
    }

    @Test
    fun responseMessage() {
        val response = newResponse(code = 200, message = "OK")
        assertEquals("OK", response.message)
    }

    @Test
    fun responseProtocol() {
        val response = newResponse(code = 200, protocol = Protocol.HTTP_2)
        assertEquals(Protocol.HTTP_2, response.protocol)
    }

    @Test
    fun responseHeaders() {
        val response = ResponseBuilder()
            .request(RequestBuilder().url("https://example.com/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", "text/plain")
            .addHeader("X-Custom", "value1")
            .addHeader("X-Custom", "value2")
            .build()

        assertEquals("text/plain", response.headers["Content-Type"])
        assertEquals(listOf("value1", "value2"), response.headers("X-Custom"))
    }

    @Test
    fun isSuccessful() {
        assertTrue(newResponse(code = 200).isSuccessful)
        assertTrue(newResponse(code = 201).isSuccessful)
        assertTrue(newResponse(code = 299).isSuccessful)
        assertFalse(newResponse(code = 199).isSuccessful)
        assertFalse(newResponse(code = 300).isSuccessful)
        assertFalse(newResponse(code = 400).isSuccessful)
        assertFalse(newResponse(code = 500).isSuccessful)
    }

    @Test
    fun isRedirect() {
        assertFalse(newResponse(code = 200).isRedirect)
        assertTrue(newResponse(code = 301).isRedirect)
        assertTrue(newResponse(code = 302).isRedirect)
        assertTrue(newResponse(code = 303).isRedirect)
        assertTrue(newResponse(code = 307).isRedirect)
        assertTrue(newResponse(code = 308).isRedirect)
        assertFalse(newResponse(code = 304).isRedirect) // Not Modified is not a redirect
        assertFalse(newResponse(code = 400).isRedirect)
    }

    @Test
    fun newBuilder() {
        val original = newResponse(code = 200)
        val modified = original.newBuilder()
            .code(201)
            .message("Created")
            .build()

        assertEquals(200, original.code)
        assertEquals(201, modified.code)
        assertEquals("Created", modified.message)
    }

    @Test
    fun cacheControl() {
        val response = ResponseBuilder()
            .request(RequestBuilder().url("https://example.com/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Cache-Control", "max-age=3600, no-cache")
            .build()

        assertEquals(3600, response.cacheControl.maxAgeSeconds)
        assertTrue(response.cacheControl.noCache)
    }

    @Test
    fun networkResponseAndCacheResponse() {
        val networkResponse = newResponse(code = 200)
        val cacheResponse = newResponse(code = 200)

        val response = newResponse(code = 200)
            .newBuilder()
            .networkResponse(networkResponse)
            .cacheResponse(cacheResponse)
            .build()

        assertEquals(networkResponse, response.networkResponse)
        assertEquals(cacheResponse, response.cacheResponse)
    }

    @Test
    fun priorResponse() {
        val priorResponse = newResponse(code = 301)
        val response = newResponse(code = 200)
            .newBuilder()
            .priorResponse(priorResponse)
            .build()

        assertEquals(priorResponse, response.priorResponse)
    }

    @Test
    fun timestamps() {
        val response = ResponseBuilder()
            .request(RequestBuilder().url("https://example.com/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .sentRequestAtMillis(1000L)
            .receivedResponseAtMillis(2000L)
            .build()

        assertEquals(1000L, response.sentRequestAtMillis)
        assertEquals(2000L, response.receivedResponseAtMillis)
    }

    private fun newResponse(
        code: Int = 200,
        message: String = "OK",
        protocol: Protocol = Protocol.HTTP_1_1,
    ): Response = ResponseBuilder()
        .request(
            RequestBuilder()
                .url("https://example.com/")
                .build(),
        )
        .protocol(protocol)
        .code(code)
        .message(message)
        .build()
}
