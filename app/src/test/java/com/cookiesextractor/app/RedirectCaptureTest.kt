package com.cookiesextractor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedirectCaptureTest {

    // ---- shouldCaptureScheme: WebView-loadable schemes are never captured ----

    @Test
    fun webviewLoadableSchemesAreNotCaptured() {
        for (scheme in listOf("http", "https", "HTTP", "Https", "about", "data", "blob", "content", "mailto", "tel")) {
            assertFalse("scheme: $scheme", RedirectCapture.shouldCaptureScheme(scheme))
        }
    }

    @Test
    fun nullSchemeIsNotCaptured() {
        assertFalse(RedirectCapture.shouldCaptureScheme(null))
    }

    @Test
    fun loadableSchemePolicyIsExactlyTheDocumentedSet() {
        assertEquals(
            setOf("http", "https", "about", "data", "blob", "content", "mailto", "tel"),
            RedirectCapture.loadableSchemes
        )
    }

    // ---- shouldReleaseCapture: origin-bound invalidation ----

    @Test
    fun sameOriginFallbackKeepsTheCapture() {
        assertFalse(
            RedirectCapture.shouldReleaseCapture("https://example.com/done", "https://example.com/auth")
        )
    }

    @Test
    fun differentSiteReleasesTheCapture() {
        assertTrue(
            RedirectCapture.shouldReleaseCapture("https://other.example.org/", "https://idp.example.com/auth")
        )
    }

    @Test
    fun siblingHostWithinTheSameSiteKeepsTheCapture() {
        assertFalse(
            RedirectCapture.shouldReleaseCapture("https://www.example.com/done", "https://idp.example.com/consent")
        )
    }

    @Test
    fun hostComparisonIsCaseInsensitive() {
        assertFalse(
            RedirectCapture.shouldReleaseCapture("https://EXAMPLE.com/done", "https://example.com/auth")
        )
    }

    @Test
    fun schemeChangeReleasesTheCapture() {
        assertTrue(
            RedirectCapture.shouldReleaseCapture("http://example.com/", "https://example.com/")
        )
    }

    @Test
    fun nullCurrentUrlOrOriginKeepsTheCapture() {
        assertFalse(RedirectCapture.shouldReleaseCapture(null, "https://example.com/"))
        assertFalse(RedirectCapture.shouldReleaseCapture("https://example.com/", null))
    }

    @Test
    fun hostlessCurrentUrlKeepsTheCapture() {
        assertFalse(
            RedirectCapture.shouldReleaseCapture("urn:ietf:wg:oauth:2.0:oob?code=x", "https://example.com/")
        )
    }

    // ---- shouldCaptureScheme: everything else is captured ----

    @Test
    fun nonLoadableSchemesAreCaptured() {
        // javascript: is pinned here even though it is unreachable at runtime
        // (Blink consumes javascript: URLs before any WebViewClient callback).
        for (scheme in listOf("urn", "URN", "intent", "file", "javascript", "myapp", "MYAPP")) {
            assertTrue("scheme: $scheme", RedirectCapture.shouldCaptureScheme(scheme))
        }
    }

    // ---- isLoadablePageUrl / isAndroidAssetUrl: the bundled-asset allowance ----

    @Test
    fun loadablePageUrlAllowsBundledAssetsAndNothingElseInFileScheme() {
        // The bundled guide: loadable from the address bar, bookmarks, and the menu.
        assertTrue(RedirectCapture.isLoadablePageUrl("file:///android_asset/guide.html"))
        // The generator produces exactly what the classifier accepts (no desync).
        assertEquals("file:///android_asset/guide.html", RedirectCapture.androidAssetUrl("guide.html"))
        assertTrue(RedirectCapture.isLoadablePageUrl(RedirectCapture.androidAssetUrl("guide.html")))
        // Scheme compares case-insensitively (matching shouldCaptureScheme), path exactly.
        assertTrue(RedirectCapture.isLoadablePageUrl("FILE:///android_asset/guide.html"))
        assertFalse(RedirectCapture.isLoadablePageUrl("file:///ANDROID_ASSET/guide.html"))
        // Every other file: URL stays a capture scheme.
        assertFalse(RedirectCapture.isLoadablePageUrl("file:///sdcard/cookies.html"))
        assertFalse(RedirectCapture.isAndroidAssetUrl("file:///sdcard/cookies.html"))
        // Pre-existing semantics unchanged.
        assertTrue(RedirectCapture.isLoadablePageUrl("https://example.com/"))
        assertTrue(RedirectCapture.isLoadablePageUrl("no-scheme-hostname"))
        assertFalse(RedirectCapture.isLoadablePageUrl("urn:ietf:wg:oauth:2.0:oob?code=x"))
        // The scheme is everything before the first colon; Uri.parse's leniency for
        // dirty pseudo-schemes is gone on purpose. Real callers normalize first (the
        // address bar, the bookmark sheet, the SPI bookmark add), so only malformed
        // input reaches these, and rejecting it at the gate beats loading it.
        assertFalse(RedirectCapture.isLoadablePageUrl(" https://example.com/"))
        assertFalse(RedirectCapture.isLoadablePageUrl("example.com/path:8080"))
    }

    // ---- shareText: parameter parsing ----

    @Test
    fun oauthParamsBecomeLinesInUrlOrder() {
        assertEquals(
            "code=test123\nsession_state=xyz",
            RedirectCapture.shareText("urn:ietf:wg:oauth:2.0:oob?code=test123&session_state=xyz")
        )
    }

    @Test
    fun percentEncodedValueIsDecoded() {
        assertEquals(
            "code=abc/def",
            RedirectCapture.shareText("urn:x?code=abc%2Fdef")
        )
    }

    @Test
    fun duplicateParamsArePreservedInOrder() {
        assertEquals(
            "a=1\nb=2\na=3",
            RedirectCapture.shareText("urn:x?a=1&b=2&a=3")
        )
    }

    @Test
    fun valueContainingEqualsKeepsItAfterTheFirstSplit() {
        assertEquals(
            "token=a=b",
            RedirectCapture.shareText("urn:x?token=a=b")
        )
    }

    @Test
    fun nameOnlySegmentSharesAsEmptyValue() {
        assertEquals(
            "flag=\ncode=1",
            RedirectCapture.shareText("urn:x?flag&code=1")
        )
    }

    // ---- shareText: verbatim fallbacks ----

    @Test
    fun emptyQueryReturnsUrlVerbatim() {
        assertEquals("urn:x?", RedirectCapture.shareText("urn:x?"))
    }

    @Test
    fun urlWithoutQueryReturnsVerbatim() {
        assertEquals("urn:ietf:wg:oauth:2.0:oob", RedirectCapture.shareText("urn:ietf:wg:oauth:2.0:oob"))
    }

    @Test
    fun undecodableQueryReturnsUrlVerbatim() {
        assertEquals("urn:x?bad=%zz", RedirectCapture.shareText("urn:x?bad=%zz"))
    }

    @Test
    fun oneBadSegmentFallsBackToWholeUrlVerbatim() {
        assertEquals(
            "urn:x?code=abc&state=50%",
            RedirectCapture.shareText("urn:x?code=abc&state=50%")
        )
    }

    @Test
    fun plusDecodesAsSpacePerRfc6749() {
        assertEquals(
            "state=hello world",
            RedirectCapture.shareText("urn:x?state=hello+world")
        )
    }

    @Test
    fun percentEncodedPlusSurvivesDecoding() {
        assertEquals(
            "code=Ab3+Xd9",
            RedirectCapture.shareText("urn:x?code=Ab3%2BXd9")
        )
    }

    @Test
    fun fragmentIsStrippedFromLastParam() {
        assertEquals(
            "code=abc\nstate=xyz",
            RedirectCapture.shareText("myapp://cb?code=abc&state=xyz#_=_")
        )
    }

    @Test
    fun queryInsideFragmentIsNotTheQuery() {
        assertEquals("urn:x#f?code=1", RedirectCapture.shareText("urn:x#f?code=1"))
    }

    @Test
    fun multiByteUtf8SequenceDecodesAsOneCharacter() {
        assertEquals(
            "user=José",
            RedirectCapture.shareText("myapp://cb?user=Jos%C3%A9")
        )
    }

    @Test
    fun invalidUtf8SequenceFallsBackToWholeUrlVerbatim() {
        assertEquals(
            "myapp://cb?user=Jos%C3",
            RedirectCapture.shareText("myapp://cb?user=Jos%C3")
        )
    }

    @Test
    fun signedHexEscapeFallsBackToWholeUrlVerbatim() {
        assertEquals("urn:x?v=%+5", RedirectCapture.shareText("urn:x?v=%+5"))
    }
}
