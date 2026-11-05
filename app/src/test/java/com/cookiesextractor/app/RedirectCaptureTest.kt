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

    // ---- isAuthorizeLanding profiles + shouldCapture: authorize landings (COK-40/46) ----

    @Test
    fun everyLandingProfileAcceptsItsCanonicalSampleThroughTheWholeRule() {
        // Each profile must survive not just its own predicate but the FULL capture
        // rule (shouldCapture conjunction) and the paste-still-loads invariant, so a
        // future profile cannot be added in a way the loadable or asset gates swallow.
        RedirectCapture.landingProfiles.forEach { profile ->
            // Real landings carry a tenant path segment before the suffix: the sample
            // keeps one, so a future required-prefix field cannot slip past untested.
            val sample = "https://${profile.hosts.first()}/tenant-xyz${profile.pathSuffix}?code=x"
            assertTrue("profile ${profile.name} must match its canonical sample", profile.matches(sample))
            assertTrue("profile ${profile.name} must be captured", RedirectCapture.shouldCapture("https", sample))
            assertTrue("profile ${profile.name} must stay loadable when pasted", RedirectCapture.isLoadablePageUrl(sample))
        }
    }

    // Synthetic GUID: real tenant ids identify an organization and this repo is public.
    private val tenantBase = "https://login.microsoftonline.com/11111111-2222-3333-4444-555555555555"

    @Test
    fun nativeClientLandingWithACodeIsTheOneHttpsCapture() {
        // The DavMail Graph PKCE redirect_uri, common and tenant-scoped flavors.
        assertTrue(
            RedirectCapture.isAuthorizeLanding(
                "https://login.microsoftonline.com/common/oauth2/nativeclient?code=Ab3.Xyz-_"
            )
        )
        assertTrue(
            RedirectCapture.isAuthorizeLanding("$tenantBase/oauth2/nativeclient?session_state=x&code=y")
        )
    }

    @Test
    fun landingWithoutACodeKeepsLoadingSoTheUserSeesTheError() {
        assertFalse(
            RedirectCapture.isAuthorizeLanding(
                "https://login.microsoftonline.com/common/oauth2/nativeclient?error=AADSTS50020"
            )
        )
        assertFalse(
            RedirectCapture.isAuthorizeLanding("https://login.microsoftonline.com/common/oauth2/nativeclient")
        )
    }

    @Test
    fun loginAndConsentPagesKeepFlowing() {
        assertFalse(
            RedirectCapture.isAuthorizeLanding("$tenantBase/oauth2/v2.0/authorize?code_challenge=x")
        )
        assertFalse(
            RedirectCapture.isAuthorizeLanding("https://login.microsoftonline.com/common/")
        )
    }

    @Test
    fun onlyTheMicrosoftHostOverHttpsIsNativeClient() {
        assertTrue(
            RedirectCapture.isAuthorizeLanding(
                "HTTPS://LOGIN.MICROSOFTONLINE.COM/common/oauth2/nativeclient?code=a"
            )
        )
        // Sovereign clouds run the same landing page.
        assertTrue(
            RedirectCapture.isAuthorizeLanding("https://login.microsoftonline.us/common/oauth2/nativeclient?code=a")
        )
        assertFalse(
            RedirectCapture.isAuthorizeLanding("https://login.microsoft.com/common/oauth2/nativeclient?code=a")
        )
        assertFalse(
            RedirectCapture.isAuthorizeLanding("https://evil.example.com/common/oauth2/nativeclient?code=a")
        )
        assertFalse(
            RedirectCapture.isAuthorizeLanding("http://login.microsoftonline.com/common/oauth2/nativeclient?code=a")
        )
    }

    @Test
    fun codeMustBeARealQueryParam() {
        assertFalse(
            RedirectCapture.isAuthorizeLanding("https://login.microsoftonline.com/common/oauth2/nativeclient?mycode=a")
        )
        assertFalse(
            RedirectCapture.isAuthorizeLanding("https://login.microsoftonline.com/common/oauth2/nativeclient#code=a")
        )
        // An empty code carries no token: keep the error-page behavior instead.
        assertFalse(
            RedirectCapture.isAuthorizeLanding("https://login.microsoftonline.com/common/oauth2/nativeclient?code=")
        )
    }

    @Test
    fun landingUrlStaysLoadableForPaste() {
        // The landing is deliberately outside the loadable gates' capture rule: pasting
        // it must keep loading (COK-38 unification must not invert this).
        assertTrue(
            RedirectCapture.isLoadablePageUrl("https://login.microsoftonline.com/common/oauth2/nativeclient?code=x")
        )
    }

    @Test
    fun shouldCaptureCombinesSchemeAndLandingRules() {
        assertTrue(RedirectCapture.shouldCapture("urn", "urn:ietf:wg:oauth:2.0:oob?code=x"))
        assertTrue(
            RedirectCapture.shouldCapture(
                "https", "https://login.microsoftonline.com/common/oauth2/nativeclient?code=a"
            )
        )
        assertFalse(RedirectCapture.shouldCapture("https", "https://example.com/"))
        assertFalse(RedirectCapture.shouldCapture("mailto", "mailto:a@b.c"))
        // The bundled assets are never carriers, even though file: is a capture scheme.
        assertFalse(
            RedirectCapture.shouldCapture("file", RedirectCapture.androidAssetUrl("guide.html"))
        )
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
