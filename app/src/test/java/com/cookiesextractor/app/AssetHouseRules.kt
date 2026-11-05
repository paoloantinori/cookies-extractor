package com.cookiesextractor.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File

/**
 * The house rules every bundled HTML asset obeys (COK-37 for guide.html, COK-45 for
 * handoff.html): present, scriptless, and free of remote resources. One shared
 * implementation so the two per-asset test classes cannot drift apart (they already
 * had: the case-insensitive escape was added on one side only).
 */
object AssetHouseRules {
    private val remoteResourceRegexes = listOf(
        Regex("""\bsrc\s*=\s*["']https?://""", RegexOption.IGNORE_CASE),
        Regex("""\bhref\s*=\s*["']https?://""", RegexOption.IGNORE_CASE),
        Regex("""url\(\s*["']?https?://""", RegexOption.IGNORE_CASE),
    )

    fun assertHouseRules(asset: File) {
        val html = asset.readText()
        assertTrue(html.isNotBlank())
        assertFalse("<script" in html.lowercase())
        for (regex in remoteResourceRegexes) assertFalse(regex.containsMatchIn(html))
    }
}
