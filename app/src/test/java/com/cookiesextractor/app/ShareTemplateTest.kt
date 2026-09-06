package com.cookiesextractor.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareTemplateTest {

    private val context = mapOf(
        "payload" to "a=1; b=2",
        "url" to "https://example.com/page",
        "title" to "Example",
        "host" to "example.com",
        "date" to "2026-09-06",
    )

    // ---- known placeholders are substituted ----

    @Test
    fun replacesEveryKnownPlaceholder() {
        val template = "{payload} {url} {title} {host} {date}"
        assertEquals(
            "a=1; b=2 https://example.com/page Example example.com 2026-09-06",
            ShareTemplate.render(template, context),
        )
    }

    @Test
    fun repeatedPlaceholdersAllSubstitute() {
        assertEquals("x.example x.example", ShareTemplate.render("{host} {host}", mapOf("host" to "x.example")))
    }

    // ---- unknown or malformed tokens stay literal ----

    @Test
    fun unknownKeyPassesThrough() {
        assertEquals("keep {nope}", ShareTemplate.render("keep {nope}", context))
    }

    @Test
    fun spacesInsideBracesAreLiteral() {
        assertEquals("{ payload }", ShareTemplate.render("{ payload }", context))
    }

    @Test
    fun unmatchedBraceIsLiteral() {
        assertEquals("{payload", ShareTemplate.render("{payload", context))
        assertEquals("payload}", ShareTemplate.render("payload}", context))
    }

    @Test
    fun digitLeadingTokenIsLiteral() {
        assertEquals("{1payload}", ShareTemplate.render("{1payload}", context))
    }

    @Test
    fun doubleBracesSubstituteTheInnerToken() {
        // the inner {payload} matches, the outer braces are literal context around it
        assertEquals("{a=1; b=2}", ShareTemplate.render("{{payload}}", context))
    }

    // ---- degenerate templates ----

    @Test
    fun emptyTemplateRendersEmpty() {
        assertEquals("", ShareTemplate.render("", context))
    }

    @Test
    fun templateWithoutPlaceholdersIsVerbatim() {
        assertEquals("just words", ShareTemplate.render("just words", context))
    }

    @Test
    fun substitutedValuesAreNotRescanned() {
        // a page title or cookie value containing token-like text must pass through
        // verbatim, never be re-expanded as a template itself
        assertEquals(
            "x {payload} y",
            ShareTemplate.render("{title}", mapOf("title" to "x {payload} y")),
        )
    }

    // ---- the placeholder list is the editor's contract ----

    @Test
    fun placeholdersListMatchesTheShareContext() {
        assertEquals(listOf("payload", "url", "title", "host", "date"), ShareTemplate.PLACEHOLDERS)
    }
}
