package com.cookiesextractor.app

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/**
 * Pure-JVM classification and parsing of OAuth redirect URLs captured from the WebView (COK-3).
 * Deliberately free of android.* imports so it runs under plain JUnit, where android.jar is
 * stubbed. All functions are total: they never throw, whatever the input.
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
     * One exception since COK-37: isLoadablePageUrl admits exactly the bundled-asset
     * area (file:///android_asset/) as app-curated content.
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

    /** The one file: area the app loads on purpose: bundled assets (the in-app guide). */
    private const val ANDROID_ASSET_PATH = "///android_asset/"

    /**
     * True for bundled-asset URLs (`file:///android_asset/...`). The scheme compares
     * case-insensitively like shouldCaptureScheme; the path compares exactly, so no
     * local-file browsing surface opens beyond the bundled pages.
     */
    fun isAndroidAssetUrl(url: String): Boolean {
        val schemeEnd = url.indexOf(':')
        if (schemeEnd <= 0) return false
        return url.substring(0, schemeEnd).equals("file", ignoreCase = true) &&
            url.startsWith(ANDROID_ASSET_PATH, schemeEnd + 1)
    }

    /**
     * Builds the bundled-asset URL for an asset [path] (e.g. "guide.html"): the one
     * generator for URLs [isAndroidAssetUrl] accepts, so the base is written once.
     */
    fun androidAssetUrl(path: String): String = "file:$ANDROID_ASSET_PATH$path"

    /**
     * True when [url] can be loaded as a page: any URL whose scheme is not a capture
     * scheme, plus the bundled-asset area. Capture-schemes must stay rejected before
     * load: their failure would route the URL into onReceivedError's capture path and
     * be mislabeled as OAuth tokens. Every MainActivity loadable gate delegates here
     * (address bar, bookmark Add row, SPI bookmark add, onCreateWindow); pure JVM so
     * the rule runs under plain JUnit.
     */
    fun isLoadablePageUrl(url: String): Boolean {
        if (isAndroidAssetUrl(url)) return true
        val schemeEnd = url.indexOf(':')
        if (schemeEnd <= 0) return true
        return !shouldCaptureScheme(url.substring(0, schemeEnd))
    }

    /** Microsoft identity hosts serving the /oauth2/nativeclient authorize landing
     *  (public cloud plus the sovereign us/cn variants, which run the same page). */
    private val nativeClientHosts =
        setOf("login.microsoftonline.com", "login.microsoftonline.us", "login.partner.microsoftonline.cn")

    /**
     * True for the Microsoft authorize landing, `https://login.microsoftonline.com/{tenant}/
     * oauth2/nativeclient?code=...`: the one https URL treated as an OAuth carrier (COK-40).
     * An authorization-code flow that registers the system-browser redirect_uri strands the
     * one-time code in a page address no share affordance reaches (the DavMail Graph PKCE
     * re-auth, verified 2026-11-04), so the capture call site classifies this landing like
     * a non-http redirect. A non-empty `code` query parameter is required: an error landing
     * (wrong account, AADSTS family) keeps loading so the user sees the error instead of
     * sharing an address with no token. Deliberately narrow (Microsoft identity hosts,
     * https only) and deliberately outside the loadable gates: pasting the landing URL in
     * the address bar still loads it.
     */
    fun isNativeClientLanding(url: String): Boolean {
        // Cheap pre-filter on this per-navigation path: every URL the checks below
        // accept contains this literal in its (case-sensitive) path, so the URI
        // parse runs only for Microsoft-ish URLs, not for every page load.
        if (!url.contains("nativeclient")) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host ?: return false
        if (host.lowercase() !in nativeClientHosts) return false
        if (!uri.path.endsWith("/oauth2/nativeclient")) return false
        return uri.rawQuery?.split('&')
            ?.any { it.startsWith("code=") && it.length > "code=".length } == true
    }

    /**
     * The one composite capture rule for in-flight navigations (the override path):
     * a non-http carrier scheme, or the one https OAuth landing ([isNativeClientLanding]),
     * but never the bundled assets (COK-37: app-curated help content must not be announced
     * as tokens). The onReceivedError fallback deliberately keeps the narrower scheme-only
     * rule: the only https URL reaching it is a pasted landing whose load failed, and
     * capturing the user's own paste would clobber a held one-time code. Pure JVM so the
     * conjunction itself runs under plain JUnit (COK-40), the same delegation pattern
     * isLoadablePageUrl gives the loadable gates.
     */
    fun shouldCapture(scheme: String?, url: String): Boolean =
        !isAndroidAssetUrl(url) && (shouldCaptureScheme(scheme) || isNativeClientLanding(url))

    /**
     * Renders [url]'s query parameters as "name=value" lines, URL order preserved, duplicates
     * kept. The fragment is stripped first (some IdPs append one; it is not a parameter),
     * values are form-decoded per RFC 6749 section 4.1.2 ('+' is a space; a literal '+'
     * arrives as %2B), and any designed decode failure falls back to the verbatim [url] so
     * a parameter is never silently dropped. Serves the debug channel's /capture; the share
     * flow sends the raw URL instead (COK-16: newline-separated lines got glued in transit).
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
     * two designed exception types, never a blanket catch. Internal: also the decoder for
     * DebugHttp query components, which are specified the same way.
     */
    internal fun percentDecode(s: String): String {
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
