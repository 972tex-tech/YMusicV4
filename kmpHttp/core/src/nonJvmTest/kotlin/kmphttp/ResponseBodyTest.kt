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
import kotlin.test.assertNull

class ResponseBodyTest {
    @Test
    fun emptyResponseBody() {
        val body = ResponseBody.EMPTY
        assertNull(body.contentType())
        assertEquals(0L, body.contentLength())
    }

    @Test
    fun emptyResponseBodyClose() {
        val body = ResponseBody.EMPTY
        // Should not throw
        body.close()
        body.close() // Multiple closes should be safe
    }

    @Test
    fun emptyResponseBodyAsyncSource() {
        val body = ResponseBody.EMPTY
        val asyncSource = body.asyncSource()
        // Should be able to close without error
        asyncSource.close()
    }

    @Test
    fun defaultResponseBodyIsEmpty() {
        val response = ResponseBuilder()
            .request(RequestBuilder().url("https://example.com/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .build()

        // Default response body
        assertNull(response.body.contentType())
        assertEquals(0L, response.body.contentLength())
    }

    @Test
    fun responseBodyFromHeaders() {
        val response = ResponseBuilder()
            .request(RequestBuilder().url("https://example.com/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Content-Length", "100")
            .build()

        // Headers are set on Response, but default body is still empty
        assertNull(response.body.contentType())
        assertEquals(0L, response.body.contentLength())
    }

    @Test
    fun responseCanBeClosedMultipleTimes() {
        val response = ResponseBuilder()
            .request(RequestBuilder().url("https://example.com/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .build()

        // Should be able to close multiple times
        response.close()
        response.close()
    }
}
