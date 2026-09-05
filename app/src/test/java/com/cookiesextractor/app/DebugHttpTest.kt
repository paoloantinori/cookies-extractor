package com.cookiesextractor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugHttpTest {

    // ---- parse: request line ----

    @Test
    fun parsesMinimalGetRequest() {
        val req = DebugHttp.parse("GET /status HTTP/1.1\r\n\r\n")
        assertEquals("/status", req?.path)
        assertEquals(emptyMap<String, String>(), req?.query)
        assertEquals(emptyMap<String, String>(), req?.headers)
    }

    @Test
    fun acceptsHttp10() {
        assertEquals("/x", DebugHttp.parse("GET /x HTTP/1.0\r\n\r\n")?.path)
    }

    @Test
    fun rejectsNonGetMethod() {
        assertNull(DebugHttp.parse("POST /status HTTP/1.1\r\n\r\n"))
    }

    @Test
    fun rejectsNonHttpVersion() {
        assertNull(DebugHttp.parse("GET /status FTP/1.1\r\n\r\n"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(DebugHttp.parse("hello"))
        assertNull(DebugHttp.parse(""))
        assertNull(DebugHttp.parse("GET /only-two-parts\r\n\r\n"))
    }

    // ---- parse: query ----

    @Test
    fun splitsPathFromQuery() {
        val req = DebugHttp.parse("GET /navigate?url=https://a.b/c HTTP/1.1\r\n\r\n")
        assertEquals("/navigate", req?.path)
        assertEquals("https://a.b/c", req?.query?.get("url"))
    }

    @Test
    fun decodesPercentAndPlusInQueryValues() {
        val req = DebugHttp.parse("GET /navigate?url=https%3A%2F%2Fa.b%2Fc+d HTTP/1.1\r\n\r\n")
        assertEquals("https://a.b/c d", req?.query?.get("url"))
    }

    @Test
    fun undecodableComponentStaysVerbatim() {
        val req = DebugHttp.parse("GET /navigate?url=a%zz HTTP/1.1\r\n\r\n")
        assertEquals("a%zz", req?.query?.get("url"))
    }

    @Test
    fun firstValueWinsOnDuplicateKeys() {
        val req = DebugHttp.parse("GET /t?a=1&a=2 HTTP/1.1\r\n\r\n")
        assertEquals("1", req?.query?.get("a"))
    }

    @Test
    fun segmentWithoutEqualsMapsToEmptyValue() {
        val req = DebugHttp.parse("GET /t?flag HTTP/1.1\r\n\r\n")
        assertEquals("", req?.query?.get("flag"))
    }

    @Test
    fun emptyQueryYieldsEmptyMap() {
        val req = DebugHttp.parse("GET /t? HTTP/1.1\r\n\r\n")
        assertEquals(emptyMap<String, String>(), req?.query)
    }

    @Test
    fun emptyValueIsPreserved() {
        val req = DebugHttp.parse("GET /t?url= HTTP/1.1\r\n\r\n")
        assertEquals("", req?.query?.get("url"))
    }

    // ---- parse: headers ----

    @Test
    fun lowercasesHeaderNamesAndTrimsValues() {
        val req = DebugHttp.parse("GET / HTTP/1.1\r\nAuthorization: Bearer abc \r\n\r\n")
        assertEquals("Bearer abc", req?.headers?.get("authorization"))
    }

    @Test
    fun ignoresMalformedHeaderLines() {
        val req = DebugHttp.parse("GET / HTTP/1.1\r\nbadline\r\nX-Ok: 1\r\n\r\n")
        assertEquals(mapOf("x-ok" to "1"), req?.headers)
    }

    // ---- jsonUnescape ----

    @Test
    fun unescapesJsonStringLiteral() {
        assertEquals("a\"b\\c\nd", DebugHttp.jsonUnescape("\"a\\\"b\\\\c\\nd\""))
    }

    @Test
    fun unescapeDecodesUnicodeEscape() {
        assertEquals("é", DebugHttp.jsonUnescape("\"\\u00e9\""))
    }

    @Test
    fun unescapeRejectsMalformedLiterals() {
        assertNull(DebugHttp.jsonUnescape("no quotes"))
        assertNull(DebugHttp.jsonUnescape("\"unterminated\\"))
        assertNull(DebugHttp.jsonUnescape("\"bad \\x escape\""))
    }
}
