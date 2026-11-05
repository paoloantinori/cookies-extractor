package com.cookiesextractor.app

import org.junit.Test
import java.io.File

/**
 * Pins the bundled conclusion page (COK-45) against the shared asset house rules
 * (scriptless, local-only; see AssetHouseRules). Guide-specific structure lives in
 * GuideAssetTest; handoff.html has no structure beyond the rules themselves.
 */
class HandoffAssetTest {

    @Test
    fun pageObeysTheAssetHouseRules() = AssetHouseRules.assertHouseRules(File("src/main/assets/handoff.html"))
}
