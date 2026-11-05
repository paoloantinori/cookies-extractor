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
    fun pageObeysTheAssetHouseRules() = AssetHouseRules.assertHouseRules(File("src/main/assets/guide.html"))
}
