package com.cookiesextractor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedirectCaptureTest {

    // ---- shouldCapture: WebView-loadable schemes are never captured ----

    @Test
    fun httpUrlIsNotCaptured() {
        assertFalse(RedirectCapture.shouldCapture("http://example.com"))
    }

    @Test
    fun httpsUrlIsNotCaptured() {
        assertFalse(RedirectCapture.shouldCapture("https://example.com/path?a=1&b=2"))
    }

    @Test
    fun uppercaseHttpsUrlIsNotCaptured() {
        assertFalse(RedirectCapture.shouldCapture("HTTPS://EXAMPLE.COM/AUTH"))
    }

    @Test
    fun uppercaseHttpUrlIsNotCaptured() {
        assertFalse(RedirectCapture.shouldCapture("HTTP://example.com"))
    }

    @Test
    fun opaqueHttpSchemeIsNotCaptured() {
        assertFalse(RedirectCapture.shouldCapture("http:x"))
    }

    @Test
    fun aboutBlankIsNotCaptured() {
        assertFalse(RedirectCapture.shouldCapture("about:blank"))
    }

    @Test
    fun dataUrlIsNotCaptured() {
        assertFalse(RedirectCapture.shouldCapture("data:text/html,<x>"))
    }

    @Test
    fun blobUrlIsNotCaptured() {
        assertFalse(RedirectCapture.shouldCapture("blob:https://example.com/uuid"))
    }

    @Test
    fun contentUrlIsNotCaptured() {
        assertFalse(RedirectCapture.shouldCapture("content://media/external/file/1"))
    }

    @Test
    fun schemelessUrlIsNotCaptured() {
        assertFalse(RedirectCapture.shouldCapture("example.com/path"))
    }

    @Test
    fun emptyStringIsNotCaptured() {
        assertFalse(RedirectCapture.shouldCapture(""))
    }

    // ---- shouldCapture: everything else is captured ----

    @Test
    fun oobUrnRedirectIsCaptured() {
        assertTrue(
            RedirectCapture.shouldCapture("urn:ietf:wg:oauth:2.0:oob?code=test123&session_state=xyz")
        )
    }

    @Test
    fun intentUrlIsCaptured() {
        assertTrue(RedirectCapture.shouldCapture("intent://x#Intent;end="))
    }

    @Test
    fun customSchemeCallbackIsCaptured() {
        assertTrue(RedirectCapture.shouldCapture("myapp://cb?code=x"))
    }

    @Test
    fun fileUrlIsCaptured() {
        assertTrue(RedirectCapture.shouldCapture("file:///x"))
    }

    @Test
    fun javascriptUrlIsCaptured() {
        assertTrue(RedirectCapture.shouldCapture("javascript:void(0)"))
    }

    @Test
    fun uppercaseCustomSchemeIsCaptured() {
        assertTrue(RedirectCapture.shouldCapture("MYAPP://cb"))
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
    fun plusStaysLiteralInQuery() {
        assertEquals(
            "code=Ab3+Xd9",
            RedirectCapture.shareText("urn:x?code=Ab3+Xd9")
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
}
