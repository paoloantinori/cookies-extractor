package com.cookiesextractor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieCollectorTest {

    // ---- parseCookieHeader ----

    @Test
    fun parsesSingleCookie() {
        val cookies = CookieCollector.parseCookieHeader("SID=abc123", ".google.com")
        assertEquals(1, cookies.size)
        assertEquals("SID", cookies[0].name)
        assertEquals("abc123", cookies[0].value)
        assertEquals(".google.com", cookies[0].domain)
    }

    @Test
    fun parsesMultipleCookies() {
        val cookies = CookieCollector.parseCookieHeader("SID=a; HSID=b; SSID=c", ".google.com")
        assertEquals(3, cookies.size)
        assertEquals(listOf("SID", "HSID", "SSID"), cookies.map { it.name })
    }

    @Test
    fun emptyHeaderReturnsEmpty() {
        assertTrue(CookieCollector.parseCookieHeader("", ".google.com").isEmpty())
        assertTrue(CookieCollector.parseCookieHeader("   ", ".google.com").isEmpty())
    }

    @Test
    fun whitespaceNamesAreDropped() {
        val cookies = CookieCollector.parseCookieHeader("SID=a; bogus name=y; =x; NID=b", ".google.com")
        assertEquals(listOf("SID", "NID"), cookies.map { it.name })
    }

    @Test
    fun userinfoDoesNotLeakIntoTheHost() {
        assertEquals(".example.com", CookieCollector.registrableDomain("https://user:pass@example.com/"))
    }

    @Test
    fun skipsEntriesWithoutEquals() {
        val cookies = CookieCollector.parseCookieHeader("good=val; bare; also=ok", ".x.com")
        assertEquals(2, cookies.size)
        assertEquals("good", cookies[0].name)
        assertEquals("also", cookies[1].name)
    }

    @Test
    fun valueCanContainEquals() {
        val cookies = CookieCollector.parseCookieHeader("token=abc=def==", ".x.com")
        assertEquals(1, cookies.size)
        assertEquals("abc=def==", cookies[0].value)
    }

    @Test
    fun trimsSurroundingWhitespace() {
        val cookies = CookieCollector.parseCookieHeader("  name = value  ;  b = c  ", ".x.com")
        assertEquals(2, cookies.size)
        assertEquals("name", cookies[0].name)
        assertEquals("value", cookies[0].value)
    }

    // ---- registrableDomain ----

    @Test
    fun extractsFromSubdomain() {
        assertEquals(".google.com", CookieCollector.registrableDomain("https://accounts.google.com/"))
        assertEquals(".google.com", CookieCollector.registrableDomain("https://www.google.com/"))
        assertEquals(".google.it", CookieCollector.registrableDomain("https://www.google.it/"))
    }

    @Test
    fun twoLabelHostReturnsDotPrefixed() {
        assertEquals(".google.com", CookieCollector.registrableDomain("https://google.com/"))
        assertEquals(".example.org", CookieCollector.registrableDomain("https://example.org/path"))
    }

    @Test
    fun deepSubdomainTakesLastTwo() {
        assertEquals(".google.com", CookieCollector.registrableDomain("https://a.b.c.google.com/"))
    }

    @Test
    fun handlesPortInUrl() {
        assertEquals(".example.com", CookieCollector.registrableDomain("https://www.example.com:8080/path"))
    }

    @Test
    fun caseInsensitive() {
        assertEquals(".google.com", CookieCollector.registrableDomain("https://Accounts.Google.COM/"))
    }

    @Test
    fun noSchemeReturnsEmpty() {
        assertEquals("", CookieCollector.registrableDomain("not-a-url"))
    }

    @Test
    fun singleLabelReturnsEmpty() {
        assertEquals("", CookieCollector.registrableDomain("https://localhost/"))
    }

    // ---- collect (dedup + ordering) ----

    @Test
    fun deduplicatesByNameAndDomain() {
        val sources = listOf(
            "https://accounts.google.com/" to "SID=first; HSID=a",
            "https://www.google.com/" to "SID=second; NID=b",
        )
        val cookies = CookieCollector.collect(sources)
        val sid = cookies.filter { it.name == "SID" }
        assertEquals(1, sid.size)
        assertEquals("first", sid[0].value)
        assertEquals(3, cookies.size)
    }

    @Test
    fun differentDomainsAreNotDeduplicated() {
        val sources = listOf(
            "https://www.google.com/" to "SID=com",
            "https://www.google.it/" to "SID=it",
        )
        val cookies = CookieCollector.collect(sources)
        assertEquals(2, cookies.size)
        assertEquals(".google.com", cookies[0].domain)
        assertEquals(".google.it", cookies[1].domain)
    }

    @Test
    fun nullHeadersSkipped() {
        val sources = listOf(
            "https://www.google.com/" to "A=1",
            "https://www.google.it/" to null,
        )
        assertEquals(1, CookieCollector.collect(sources).size)
    }

    @Test
    fun emptySourcesReturnEmpty() {
        assertTrue(CookieCollector.collect(emptyList()).isEmpty())
    }

    // ---- toJson ----

    @Test
    fun jsonEnvelopeVersionTwo() {
        val json = CookieCollector.toJson(
            listOf(CookieCollector.Cookie("SID", "abc", ".google.com")),
        )
        assertTrue(json.startsWith("{\"version\":2,\"cookies\":["))
        assertTrue(json.endsWith("]}"))
        assertTrue(json.contains("\"name\":\"SID\""))
        assertTrue(json.contains("\"value\":\"abc\""))
        assertTrue(json.contains("\"domain\":\".google.com\""))
    }

    @Test
    fun jsonEscapesSpecialCharacters() {
        val json = CookieCollector.toJson(
            listOf(CookieCollector.Cookie("q", "a\"b\\c\nd", ".x.com")),
        )
        assertTrue(json.contains("a\\\"b\\\\c\\nd"))
    }

    @Test
    fun emptyListProducesEmptyArray() {
        assertEquals("{\"version\":2,\"cookies\":[]}", CookieCollector.toJson(emptyList()))
    }

    @Test
    fun multipleCookiesSeparatedByComma() {
        val json = CookieCollector.toJson(
            listOf(
                CookieCollector.Cookie("A", "1", ".x.com"),
                CookieCollector.Cookie("B", "2", ".y.com"),
            ),
        )
        assertTrue(json.contains("},{"))
    }

    // ---- collectAndSerialize integration ----

    @Test
    fun fullRoundTrip() {
        val sources = listOf(
            "https://myaccount.google.com/" to "SID=x; HSID=y",
            "https://www.google.it/" to "NID=z",
        )
        val json = CookieCollector.collectAndSerialize(sources)
        assertTrue(json.contains("\"version\":2"))
        assertTrue(json.contains("\"name\":\"SID\""))
        assertTrue(json.contains("\"domain\":\".google.com\""))
        assertTrue(json.contains("\"domain\":\".google.it\""))
    }

    @Test
    fun defaultExtraUrlsListIsTheGoogleFamily() {
        assertEquals(
            listOf("https://accounts.google.com/", "https://www.google.com/", "https://www.google.it/"),
            CookieCollector.DEFAULT_EXTRA_URLS,
        )
    }

    // ---- parseExtraUrls (COK-32) ----

    @Test
    fun parseExtraUrlsNormalizesBareHostsToHttps() {
        assertEquals(
            listOf("https://gitlab.example.com", "https://example.org"),
            CookieCollector.parseExtraUrls("gitlab.example.com\nexample.org"),
        )
    }

    @Test
    fun parseExtraUrlsKeepsSchemesAndSkipsCommentsAndBlanks() {
        assertEquals(
            listOf("http://lan.host:8901", "https://a.example"),
            CookieCollector.parseExtraUrls("# comment\n\nhttp://lan.host:8901   # trailing\nhttps://a.example"),
        )
    }

    @Test
    fun parseExtraUrlsDropsInvalidEntriesSilently() {
        assertEquals(emptyList<String>(), CookieCollector.parseExtraUrls("not a url\n://broken\nlocalhost\n%%%\n"))
    }

    @Test
    fun parseExtraUrlsRejectsDotBearingPseudoSchemes() {
        // an IME-joined line like "example.orghttps://x" contains :// but is garbage
        assertEquals(
            emptyList<String>(),
            CookieCollector.parseExtraUrls("example.orghttps://accounts.google.com/"),
        )
    }

    @Test
    fun collectKeepsDotlessHostCookiesLabeledWithTheFullHost() {
        // localhost/intranet pages must not silently empty the structured share
        val cookies = CookieCollector.collect(listOf("http://localhost:8080/auth" to "SID=abc; theme=dark"))
        assertEquals(2, cookies.size)
        assertEquals("localhost", cookies[0].domain)
    }

    @Test
    fun collectLabelsIpv6HostsWithBrackets() {
        val cookies = CookieCollector.collect(listOf("https://[2001:db8::1]:8443/" to "SID=v6"))
        assertEquals(1, cookies.size)
        assertEquals("[2001:db8::1]", cookies[0].domain)
    }

    @Test
    fun queryOnlyUrlDoesNotLeakIntoTheDomain() {
        // https://sso.example.com?next=1 must label .example.com, not a mangled host
        val cookies = CookieCollector.collect(listOf("https://sso.example.com?next=1" to "SID=q"))
        assertEquals(".example.com", cookies.single().domain)
    }

    @Test
    fun parseExtraUrlsEmptyInputYieldsEmptyList() {
        assertEquals(emptyList<String>(), CookieCollector.parseExtraUrls("  \n# only comments\n"))
    }
}
