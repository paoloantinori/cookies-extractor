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

    // ---- shouldCaptureScheme: everything else is captured ----

    @Test
    fun nonLoadableSchemesAreCaptured() {
        // javascript: is pinned here even though it is unreachable at runtime
        // (Blink consumes javascript: URLs before any WebViewClient callback).
        for (scheme in listOf("urn", "URN", "intent", "file", "javascript", "myapp", "MYAPP")) {
            assertTrue("scheme: $scheme", RedirectCapture.shouldCaptureScheme(scheme))
        }
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
