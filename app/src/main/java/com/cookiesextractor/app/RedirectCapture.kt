package com.cookiesextractor.app

import java.nio.ByteBuffer

/**
 * Pure-JVM classification and parsing of OAuth redirect URLs captured from the WebView (COK-3).
 * Deliberately free of android.* imports so it runs under plain JUnit, where android.jar is
 * stubbed. Both functions are total: they never throw, whatever the WebView hands them.
 */
object RedirectCapture {

    /**
     * Schemes the WebView itself can load, plus mailto:/tel: which never carry OAuth data:
     * letting the WebView fail them restores the pre-capture behavior (its own error page)
     * instead of a misleading "tokens captured" action. about:/data:/blob:/content: are
     * here because the WebView renders or resolves them natively (internal pages, inlined
     * HTML, generated files, document links); intercepting them would break ordinary
     * browsing. Everything else (urn:, intent:, custom app schemes, file:) cannot produce
     * a page, so its query string would be lost with an error page unless we capture it.
     */
    private val loadableSchemes =
        setOf("http", "https", "about", "data", "blob", "content", "mailto", "tel")

    /** True iff [url] has a scheme the WebView cannot load itself. */
    fun shouldCapture(url: String): Boolean {
        // Fast path for the dominant case: this runs for every navigation and subframe load.
        // Only well-formed http(s) matches, so everything else still reaches the real parse.
        if (url.regionMatches(0, "http://", 0, 7, ignoreCase = true) ||
            url.regionMatches(0, "https://", 0, 8, ignoreCase = true)
        ) return false
        return shouldCaptureScheme(schemeOf(url))
    }

    /** Same decision for callers already holding a parsed scheme (no string round-trip). */
    fun shouldCaptureScheme(scheme: String?): Boolean =
        scheme != null && scheme.lowercase() !in loadableSchemes

    /**
     * Renders [url]'s query parameters as "name=value" lines, URL order preserved, duplicates
     * kept. The fragment is stripped first (some IdPs append one; it is not a parameter),
     * values are percent-decoded with '+' left literal per RFC 3986, and ANY undecodable
     * segment falls back to the verbatim [url] so a parameter is never silently dropped.
     */
    fun shareText(url: String): String {
        val noFragment = url.substringBefore('#')
        val queryStart = noFragment.indexOf('?')
        if (queryStart < 0) return url
        val rawQuery = noFragment.substring(queryStart + 1)
        if (rawQuery.isEmpty()) return url
        val lines = runCatching {
            rawQuery.split('&')
                .filter { it.isNotEmpty() }
                .map { segment ->
                    val name = percentDecode(segment.substringBefore('='))
                    val value = percentDecode(segment.substringAfter('=', ""))
                    "$name=$value"
                }
        }.getOrNull() ?: return url
        return lines.joinToString("\n")
    }

    /**
     * RFC 3986 percent-decoding over raw bytes. Unlike java.net.URLDecoder this treats '+'
     * as a literal character: query strings are not form bodies, and base64-ish token
     * values containing '+' must survive intact. Multi-byte UTF-8 sequences decode as one
     * character each, and any malformed escape or invalid UTF-8 throws so the caller falls
     * back to the verbatim URL instead of sharing a corrupted value.
     */
    private fun percentDecode(s: String): String {
        fun hexValue(b: Byte): Int = when (b) {
            in 0x30..0x39 -> b - 0x30
            in 0x41..0x46 -> b - 0x37
            in 0x61..0x66 -> b - 0x57
            else -> throw IllegalArgumentException("not a hex digit")
        }
        val src = s.toByteArray(Charsets.UTF_8)
        val out = ArrayList<Byte>(src.size)
        var i = 0
        while (i < src.size) {
            val b = src[i]
            if (b != '%'.code.toByte()) {
                out.add(b)
                i++
                continue
            }
            if (i + 2 >= src.size) throw IllegalArgumentException("truncated percent escape")
            out.add(((hexValue(src[i + 1]) shl 4) or hexValue(src[i + 2])).toByte())
            i += 3
        }
        return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(out.toByteArray())).toString()
    }

    private fun schemeOf(url: String): String? {
        val colon = url.indexOf(':')
        return if (colon <= 0) null else url.substring(0, colon).lowercase()
    }
}
