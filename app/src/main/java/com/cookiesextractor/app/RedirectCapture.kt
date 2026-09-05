package com.cookiesextractor.app

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

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
     * javascript: is classified as capturable but is unreachable at runtime: Blink consumes
     * javascript: URLs in the renderer before any WebViewClient callback fires (the test
     * pins that dead classification on purpose). Visible internal for the policy-equality
     * test only.
     */
    internal val loadableSchemes =
        setOf("http", "https", "about", "data", "blob", "content", "mailto", "tel")

    /** True iff [scheme] is one the WebView cannot load itself; null (no scheme) is not captured. */
    fun shouldCaptureScheme(scheme: String?): Boolean =
        scheme != null && scheme.lowercase() !in loadableSchemes

    /**
     * Renders [url]'s query parameters as "name=value" lines, URL order preserved, duplicates
     * kept. The fragment is stripped first (some IdPs append one; it is not a parameter),
     * values are form-decoded per RFC 6749 section 4.1.2 ('+' is a space; a literal '+'
     * arrives as %2B), and any designed decode failure falls back to the verbatim [url] so
     * a parameter is never silently dropped.
     */
    fun shareText(url: String): String {
        val noFragment = url.substringBefore('#')
        val queryStart = noFragment.indexOf('?')
        if (queryStart < 0) return url
        val rawQuery = noFragment.substring(queryStart + 1)
        if (rawQuery.isEmpty()) return url
        val lines = try {
            rawQuery.split('&')
                .filter { it.isNotEmpty() }
                .map { segment ->
                    val name = percentDecode(segment.substringBefore('='))
                    val value = percentDecode(segment.substringAfter('=', ""))
                    "$name=$value"
                }
        } catch (e: IllegalArgumentException) {
            return url
        } catch (e: CharacterCodingException) {
            return url
        }
        return lines.joinToString("\n")
    }

    /**
     * RFC 6749 section 4.1.2 form-decoding over raw bytes: '+' is a space, everything else
     * is strict percent-decoding. Multi-byte UTF-8 sequences decode as one character each;
     * a malformed escape or invalid UTF-8 throws (IllegalArgumentException or
     * CharacterCodingException) so the caller falls back to the verbatim URL instead of
     * sharing a corrupted value. Unlike java.net.URLDecoder, failures are limited to the
     * two designed exception types, never a blanket catch.
     */
    private fun percentDecode(s: String): String {
        val src = s.toByteArray(Charsets.UTF_8)
        val out = ArrayList<Byte>(src.size)
        var i = 0
        while (i < src.size) {
            val b = src[i]
            when {
                b == '+'.code.toByte() -> out.add(' '.code.toByte())
                b != '%'.code.toByte() -> out.add(b)
                else -> {
                    if (i + 2 >= src.size) throw IllegalArgumentException("truncated percent escape")
                    val hi = Character.digit((src[i + 1].toInt() and 0xFF).toChar(), 16)
                    val lo = Character.digit((src[i + 2].toInt() and 0xFF).toChar(), 16)
                    if (hi < 0 || lo < 0) throw IllegalArgumentException("not a hex digit")
                    out.add(((hi shl 4) or lo).toByte())
                    i += 2
                }
            }
            i++
        }
        return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(out.toByteArray())).toString()
    }
}
