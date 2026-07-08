/*
 * Copyright (C) 2013 Square, Inc.
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
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestTest {
    @Test
    fun constructorTest() {
        val url = "https://example.com/".toHttpUrl()
        val body = "hello".toRequestBody()
        val headers = HeadersBuilder().add("User-Agent", "RequestTest").build()
        val method = "PUT"
        val request = RequestBuilder()
            .url(url)
            .headers(headers)
            .method(method, body)
            .build()
        assertEquals(url, request.url)
        assertEquals(headers, request.headers)
        assertEquals(method, request.method)
        assertEquals(body, request.body)
    }

    @Test
    fun constructorDefaultsToGet() {
        val url = "https://example.com/".toHttpUrl()
        val headers = HeadersBuilder().add("User-Agent", "RequestTest").build()
        val request = RequestBuilder()
            .url(url)
            .headers(headers)
            .build()
        assertEquals(url, request.url)
        assertEquals(headers, request.headers)
        assertEquals("GET", request.method)
        assertNull(request.body)
    }

    @Test
    fun stringBody() {
        val contentType = "text/plain; charset=utf-8".toMediaType()
        val body = "abc".toRequestBody(contentType)
        assertEquals(contentType, body.contentType())
        assertEquals(3, body.contentLength())
    }

    @Test
    fun stringWithDefaultCharsetAdded() {
        val contentType = "text/plain".toMediaType()
        val body = "\u0800".toRequestBody(contentType)
        assertEquals("text/plain; charset=utf-8".toMediaType(), body.contentType())
        assertEquals(3, body.contentLength())
    }

    @Test
    fun stringWithNonDefaultCharsetSpecified() {
        val contentType = "text/plain; charset=utf-16be".toMediaType()
        val body = try {
            "\u0800".toRequestBody(contentType)
        } catch (e: IllegalArgumentException) {
            // expect crash on non-jvm
            assertNotEquals(BUILD_TARGET, KmpBuildTarget.JVM)
            assertContains(e.message.orEmpty(), "Non-JVM platforms only support UTF-8")
            return
        }
        assertEquals(contentType, body.contentType())
        assertEquals(2, body.contentLength())
    }

    @Test
    fun byteArrayBody() {
        val contentType = "text/plain".toMediaType()
        val body = "abc".encodeToByteArray().toRequestBody(contentType)
        assertEquals(contentType, body.contentType())
        assertEquals(3, body.contentLength())
    }

    @Test
    fun byteArrayRangeBody() {
        val contentType = "text/plain".toMediaType()
        val body = ".abcd".encodeToByteArray().toRequestBody(contentType, 1, 3)
        assertEquals(contentType, body.contentType())
        assertEquals(3, body.contentLength())
    }

    /** Common verbs used for APIs such as GitHub, AWS, and Google Cloud. */
    @Test
    fun crudVerbs() {
        val contentType = "application/json".toMediaType()
        val body = "{}".toRequestBody(contentType)

        val get = RequestBuilder()
            .url("http://localhost/api")
            .get()
            .build()
        assertEquals("GET", get.method)
        assertNull(get.body)

        val head = RequestBuilder()
            .url("http://localhost/api")
            .head()
            .build()
        assertEquals("HEAD", head.method)
        assertNull(head.body)

        val delete = RequestBuilder()
            .url("http://localhost/api")
            .deleteWithBody(RequestBody.EMPTY)
            .build()
        assertEquals("DELETE", delete.method)
        assertEquals(0L, delete.body!!.contentLength())

        val post = RequestBuilder()
            .url("http://localhost/api")
            .post(body)
            .build()
        assertEquals("POST", post.method)
        assertEquals(body, post.body)

        val put = RequestBuilder()
            .url("http://localhost/api")
            .put(body)
            .build()
        assertEquals("PUT", put.method)
        assertEquals(body, put.body)

        val patch = RequestBuilder()
            .url("http://localhost/api")
            .patch(body)
            .build()
        assertEquals("PATCH", patch.method)
        assertEquals(body, patch.body)

        val query = RequestBuilder()
            .url("http://localhost/api")
            .query(body)
            .build()
        assertEquals("QUERY", query.method)
        assertEquals(body, query.body)
    }

    @Test
    fun newBuilderUrlResetsUrl() {
        val requestWithoutCache = RequestBuilder().url("http://localhost/api").build()
        val builtRequestWithoutCache = requestWithoutCache.newBuilder().url("http://localhost/api/foo").build()
        assertEquals("http://localhost/api/foo".toHttpUrl(), builtRequestWithoutCache.url)

        val requestWithCache = RequestBuilder()
            .url("http://localhost/api")
            .build()
        // cache url object
        requestWithCache.url
        val builtRequestWithCache = requestWithCache
            .newBuilder()
            .url("http://localhost/api/foo")
            .build()
        assertEquals("http://localhost/api/foo".toHttpUrl(), builtRequestWithCache.url)
    }

    @Test
    fun cacheControl() {
        val request = RequestBuilder()
            .cacheControl(CacheControlBuilder().noCache().build())
            .url("https://square.com")
            .build()
        assertEquals(listOf("no-cache"), request.headers("Cache-Control"))
        assertTrue(request.cacheControl.noCache)
    }

    @Test
    fun emptyCacheControlClearsAllCacheControlHeaders() {
        val request = RequestBuilder()
            .header("Cache-Control", "foo")
            .cacheControl(CacheControlBuilder().build())
            .url("https://square.com")
            .build()
        assertTrue(request.headers("Cache-Control").isEmpty())
    }

    @Test
    fun headerAcceptsPermittedCharacters() {
        val builder = RequestBuilder()
        builder.header("AZab09~", "AZab09 ~")
        builder.addHeader("AZab09~", "AZab09 ~")
    }

    @Test
    fun emptyNameForbidden() {
        val builder = RequestBuilder()
        assertFailsWith<IllegalArgumentException> {
            builder.header("", "Value")
        }
        assertFailsWith<IllegalArgumentException> {
            builder.addHeader("", "Value")
        }
    }

    @Test
    fun headerAllowsTabOnlyInValues() {
        val builder = RequestBuilder()
        builder.header("key", "sample\tvalue")
        assertFailsWith<IllegalArgumentException> {
            builder.header("sample\tkey", "value")
        }
    }

    @Test
    fun headerForbidsControlCharacters() {
        assertForbiddenHeader("\u0000")
        assertForbiddenHeader("\r")
        assertForbiddenHeader("\n")
        assertForbiddenHeader("\u001f")
        assertForbiddenHeader("\u007f")
        assertForbiddenHeader("\u0080")
        assertForbiddenHeader("\ud83c\udf69")
    }

    private fun assertForbiddenHeader(s: String) {
        val builder = RequestBuilder()
        assertFailsWith<IllegalArgumentException> {
            builder.header(s, "Value")
        }
        assertFailsWith<IllegalArgumentException> {
            builder.addHeader(s, "Value")
        }
        assertFailsWith<IllegalArgumentException> {
            builder.header("Name", s)
        }
        assertFailsWith<IllegalArgumentException> {
            builder.addHeader("Name", s)
        }
    }

    @Test
    fun requestToStringRedactsSensitiveHeaders() {
        val headers = HeadersBuilder()
            .add("content-length", "99")
            .add("authorization", "peanutbutter")
            .add("proxy-authorization", "chocolate")
            .add("cookie", "drink=coffee")
            .add("set-cookie", "accessory=sugar")
            .add("user-agent", "OkHttp")
            .build()
        val request = RequestBuilder()
            .url("https://square.com")
            .headers(headers)
            .build()
        assertEquals(
            "Request{method=GET, url=https://square.com/, headers=[" +
                "content-length:99," +
                " authorization:██," +
                " proxy-authorization:██," +
                " cookie:██," +
                " set-cookie:██," +
                " user-agent:OkHttp" +
                "]}",
            request.toString(),
        )
    }
}
