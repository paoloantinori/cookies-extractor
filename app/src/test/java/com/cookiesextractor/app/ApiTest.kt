package com.cookiesextractor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiTest {

    private fun parse(path: String, query: Map<String, String> = emptyMap()) =
        Api.parse(path, query)

    // ---- routing: every capability parses to its command ----

    @Test
    fun infoRouteParses() {
        assertEquals(Api.Command.Info, parse("/api/v1/info").let { (it as Api.Parse.Ok).command })
    }

    @Test
    fun allObjectRoutesParse() {
        val routes = mapOf(
            "/api/v1/status" to Api.Command.Status,
            "/api/v1/text" to Api.Command.Text,
            "/api/v1/cookies" to Api.Command.Cookies(structured = false),
            "/api/v1/capture" to Api.Command.Capture,
            "/api/v1/clear" to Api.Command.Clear,
            "/api/v1/bookmarks" to Api.Command.Bookmarks,
            "/api/v1/template" to Api.Command.Template,
        )
        routes.forEach { (path, cmd) ->
            assertEquals("route $path", cmd, (parse(path) as Api.Parse.Ok).command)
        }
    }

    @Test
    fun navigateDefaultsToFireAndForget() {
        val cmd = (parse("/api/v1/navigate", mapOf("url" to "https://x.example")) as Api.Parse.Ok).command
        assertEquals(Api.Command.Navigate("https://x.example", waitForLoad = false), cmd)
    }

    @Test
    fun navigateWaitLoadIsHonored() {
        val cmd = (parse("/api/v1/navigate", mapOf("url" to "https://x.example", "wait" to "load")) as Api.Parse.Ok).command
        assertEquals(Api.Command.Navigate("https://x.example", waitForLoad = true), cmd)
    }

    @Test
    fun navigateWithoutUrlIsRejected() {
        val err = parse("/api/v1/navigate") as Api.Parse.Err
        assertEquals(400, err.status)
        assertEquals("missing_url", err.code)
    }

    @Test
    fun bookmarkAddParsesUrlAndOptionalTitle() {
        val withTitle = (parse("/api/v1/bookmarks/add", mapOf("url" to "https://a.example", "title" to "A")) as Api.Parse.Ok).command
        assertEquals(Api.Command.BookmarkAdd("https://a.example", "A"), withTitle)
        val blankTitle = (parse("/api/v1/bookmarks/add", mapOf("url" to "https://a.example", "title" to "  ")) as Api.Parse.Ok).command
        assertEquals(Api.Command.BookmarkAdd("https://a.example", null), blankTitle)
    }

    @Test
    fun bookmarkDeleteRequiresUrl() {
        assertEquals("missing_url", (parse("/api/v1/bookmarks/delete") as Api.Parse.Err).code)
    }

    @Test
    fun templateSetAcceptsEmptyMeaningClear() {
        val cmd = (parse("/api/v1/template/set", emptyMap()) as Api.Parse.Ok).command
        assertEquals(Api.Command.TemplateSet(""), cmd)
    }

    @Test
    fun consoleLineCountIsClamped() {
        assertEquals(50, (parse("/api/v1/console") as Api.Parse.Ok).let { (it.command as Api.Command.Console).lines })
        assertEquals(
            DebugChannelServer.MAX_CONSOLE_LINES,
            (parse("/api/v1/console", mapOf("n" to "99999")) as Api.Parse.Ok).let { (it.command as Api.Command.Console).lines },
        )
        assertEquals(1, (parse("/api/v1/console", mapOf("n" to "0")) as Api.Parse.Ok).let { (it.command as Api.Command.Console).lines })
    }

    @Test
    fun fillRequiresSelectorAndParsesValue() {
        assertEquals("missing_selector", (parse("/api/v1/fill", mapOf("value" to "x")) as Api.Parse.Err).code)
        val cmd = (parse("/api/v1/fill", mapOf("selector" to "#user", "value" to "bob")) as Api.Parse.Ok).command
        assertEquals(Api.Command.Fill("#user", "bob"), cmd)
    }

    @Test
    fun submitSelectorIsOptional() {
        assertEquals(Api.Command.Submit(null), (parse("/api/v1/submit") as Api.Parse.Ok).command)
        assertEquals(Api.Command.Submit("#go"), (parse("/api/v1/submit", mapOf("selector" to "#go")) as Api.Parse.Ok).command)
    }

    @Test
    fun normalizeUserUrlPassesSchemesAndPrefixesBareInput() {
        assertEquals("https://x.example", Api.normalizeUserUrl("x.example"))
        assertEquals("http://x.example/", Api.normalizeUserUrl("http://x.example/"))
        assertEquals("ftp://x", Api.normalizeUserUrl("ftp://x"))
        assertEquals("https://", Api.normalizeUserUrl("https://"))
    }

    @Test
    fun cookiesFormatParameterSelectsStructured() {
        assertEquals(
            Api.Command.Cookies(structured = false),
            (parse("/api/v1/cookies") as Api.Parse.Ok).command,
        )
        assertEquals(
            Api.Command.Cookies(structured = true),
            (parse("/api/v1/cookies", mapOf("format" to "structured")) as Api.Parse.Ok).command,
        )
        // an unknown format value keeps the backward-compatible flat response
        assertEquals(
            Api.Command.Cookies(structured = false),
            (parse("/api/v1/cookies", mapOf("format" to "bogus")) as Api.Parse.Ok).command,
        )
    }

    @Test
    fun unknownRouteAndBarePrefixAre404() {
        for (path in listOf("/api/v1/nope", "/api/v2/info", "/api/v1")) {
            val err = parse(path) as Api.Parse.Err
            assertEquals("route $path", 404, err.status)
            assertEquals("unknown_route", err.code)
        }
    }

    // ---- error and info contracts ----

    @Test
    fun errorJsonShape() {
        assertEquals(
            "{\"error\":{\"code\":\"missing_url\",\"message\":\"url parameter is required\"}}",
            Api.errorJson("missing_url", "url parameter is required"),
        )
    }

    @Test
    fun infoJsonAdvertisesApiLevelAndEveryCapability() {
        val json = Api.infoJson("1.2.3")
        assertTrue(json.contains("\"api_level\":1"))
        assertTrue(json.contains("\"version\":\"1.2.3\""))
        Api.CAPABILITIES.forEach { c -> assertTrue("capability $c", json.contains("\"$c\"")) }
        // capabilities must be a real JSON array, not a bare list of strings
        assertTrue(json.contains("\"capabilities\":[\"info\""))
        assertTrue(json.endsWith("\"submit\"]}"))
    }

    // ---- JS generation ----

    @Test
    fun fillScriptEmbedsSelectorAndValueSafely() {
        val js = Api.fillScript("#user", "bob\";alert(1);")
        assertTrue(js.contains("\"#user\""))
        // the value's quote is escaped, so the injected script cannot break out of the literal
        assertTrue(js.contains("bob\\\";alert(1);"))
        assertTrue(js.contains("dispatchEvent(new Event('input'"))
        assertTrue(js.endsWith("})()"))
    }

    @Test
    fun fillScriptFallsBackToNameMatch() {
        // runtime JS: querySelector('[name="'+s+'"]') with plain quotes, s concatenated
        assertTrue(Api.fillScript("user", "x").contains("[name=\"'+s+'\"]"))
    }

    @Test
    fun submitScriptWithoutSelectorTargetsFirstForm() {
        val js = Api.submitScript(null)
        assertTrue(js.contains("querySelector('form')"))
        assertTrue(js.contains("requestSubmit"))
    }

    @Test
    fun submitScriptWithSelectorEmbedsItQuoted() {
        assertTrue(Api.submitScript("#go").contains("querySelector(\"#go\")"))
    }

    // ---- jsonEscape ----

    @Test
    fun jsonEscapesTheRfc8259Set() {
        assertEquals("\\\"\\\\\\n\\r\\t", Api.jsonEscape("\"\\" + "\n\r\t"))
    }

    @Test
    fun jsonEscapesControlCharsAsHex() {
        assertEquals("\\u0001", Api.jsonEscape("\u0001"))
        assertEquals("\\f", Api.jsonEscape("\u000C"))
    }

    @Test
    fun jsonWrapsInQuotes() {
        assertEquals("\"a\"", Api.json("a"))
    }

    @Test
    fun capabilitiesListMatchesRoutedCommands() {
        // the advertised list must not drift from the router: every capability string
        // corresponds to a route the parser accepts
        val routed = listOf(
            "info" to "/api/v1/info", "status" to "/api/v1/status", "navigate" to "/api/v1/navigate?url=https://a.example",
            "text" to "/api/v1/text", "cookies" to "/api/v1/cookies", "capture" to "/api/v1/capture",
            "clear" to "/api/v1/clear", "bookmarks" to "/api/v1/bookmarks",
            "bookmarks.add" to "/api/v1/bookmarks/add?url=https://a.example",
            "bookmarks.delete" to "/api/v1/bookmarks/delete?url=https://a.example",
            "template" to "/api/v1/template", "template.set" to "/api/v1/template/set?t=x",
            "console" to "/api/v1/console", "fill" to "/api/v1/fill?selector=a&value=b",
            "submit" to "/api/v1/submit",
        )
        assertEquals(Api.CAPABILITIES.size, routed.size)
        routed.forEach { (cap, route) ->
            val path = route.substringBefore('?')
            val query = if ('?' in route) DebugHttp.parseQuery(route.substringAfter('?')) else emptyMap()
            assertTrue("capability $cap does not route", parse(path, query) is Api.Parse.Ok)
        }
        assertFalse(Api.CAPABILITIES.contains("tap"))
        assertFalse(Api.CAPABILITIES.contains("screenshot"))
    }
}
