package com.cookiesextractor.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GuideAssetTest {
    // Unit tests run with the module dir (app/) as working directory; lazy so JUnit's
    // per-method instances share one read.
    private val html by lazy { File("src/main/assets/guide.html").readText() }

    @Test
    fun assetCarriesTheFourSections() {
        assertTrue(html.isNotBlank())
        listOf("quick-start", "structured-share", "extra-domains", "share-template").forEach { anchor ->
            assertTrue("missing section anchor #$anchor", "id=\"$anchor\"" in html)
        }
    }

    @Test
    fun pageHasNoScript() = assertTrue("<script" !in html.lowercase())

    @Test
    fun pageReferencesNoRemoteResources() {
        // Visible text (the curl example) legitimately contains URLs; only
        // resource positions must be local.
        listOf(
            Regex("""\bsrc\s*=\s*["']https?://"""),
            Regex("""\bhref\s*=\s*["']https?://"""),
            Regex("""url\(\s*["']?https?://"""),
        ).forEach { pattern -> assertTrue("remote resource (${pattern})", pattern.containsMatchIn(html).not()) }
    }
}
