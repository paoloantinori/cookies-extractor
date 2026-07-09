package com.cookiesextractor.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * Single-screen host.
 *  - COK-1.2: address bar + in-app WebView
 *  - COK-1.4: FAB -> extract cookies via CookieManager (empty-state Toast)
 *  - COK-1.5: shareCookies() fires the ACTION_SEND share sheet
 *  - COK-1.3: WebAuthn bridge via androidx.webkit (asset-linked sites only; arbitrary
 *    third-party passkeys are blocked by Android's anti-phishing model — see PRD §4.3)
 *
 * Rotation is handled via android:configChanges in the manifest, so the WebView is not
 * destroyed/recreated on orientation change and the loaded page survives.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        urlField = findViewById(R.id.url_field)
        val goButton: Button = findViewById(R.id.go_button)
        val shareFab: FloatingActionButton = findViewById(R.id.share_fab)

        configureWebView()

        // Go button and the keyboard's "Go" action both load the typed URL.
        goButton.setOnClickListener { loadUrlFromField() }
        urlField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadUrlFromField()
                true
            } else {
                false
            }
        }

        // FAB extracts the cookies for the loaded domain (PRD §4.4).
        shareFab.setOnClickListener { onExtractCookies() }

        // Back button traverses WebView history before exiting the app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        // Cold start: load the default URL so the address bar and WebView stay in sync.
        if (savedInstanceState == null) {
            urlField.setText(getString(R.string.default_url))
            webView.loadUrl(getString(R.string.default_url))
        }
    }

    override fun onDestroy() {
        // Release the WebView's renderer/native resources on genuine teardown. Rotation is
        // handled via configChanges, so onDestroy only runs on real finish()/destroy.
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun configureWebView() {
        with(webView.settings) {
            javaScriptEnabled = true   // PRD §4.3
            domStorageEnabled = true   // PRD §4.3
        }
        webView.webViewClient = WebViewClient() // keep navigation in-app (PRD §4.2/§4.3)

        // Accept and keep cookies so CookieManager can read them back after login. Third-party
        // cookies are enabled so the WebView stores them and will send the ones scoped to the
        // loaded domain when getCookie(url) is called. Note: getCookie returns only cookies
        // scoped to the loaded URL — a cookie set on a fully separate domain (e.g. an IdP)
        // won't appear here; cross-domain aggregation is future work.
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        enableWebAuthnIfNeeded()
    }

    /**
     * Enables the WebView <-> Credential Manager WebAuthn bridge (androidx.webkit 1.14.0),
     * gated by feature support. NOTE: Android requires Digital Asset Linking — passkeys
     * here only fire for sites that host /.well-known/assetlinks.json declaring THIS app's
     * package + signing cert. Arbitrary third-party passkey-only sites will NOT work (by
     * design, to prevent phishing). See PRD §4.3 and COK-1.6.
     */
    private fun enableWebAuthnIfNeeded() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
            WebSettingsCompat.setWebAuthenticationSupport(
                webView.settings,
                WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP,
            )
        } else {
            // Aids COK-1.6 device verification: distinguishes "bridge armed" from unsupported.
            Log.w(TAG, "WebAuthn bridge not supported on this WebView; passkeys won't fire.")
        }
    }

    /** Reads the session cookies for the currently loaded URL (PRD §4.4). */
    private fun onExtractCookies() {
        val cookies = extractCookies()
        if (cookies.isBlank()) {
            Toast.makeText(this, R.string.toast_no_cookies, Toast.LENGTH_SHORT).show()
        } else {
            // Full string goes to logcat for reliable device-side verification (COK-1.6).
            Log.i(TAG, "Cookies for ${webView.url}:\n$cookies")
            shareCookies(cookies)
        }
    }

    private fun extractCookies(): String =
        webView.url?.let { CookieManager.getInstance().getCookie(it) }.orEmpty()

    /**
     * Shares the extracted cookies via the Android share sheet (ACTION_SEND, text/plain),
     * wrapped in Intent.createChooser() per PRD §4.5.
     */
    private fun shareCookies(cookies: String) {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, getString(R.string.share_preamble, cookies))
        }
        startActivity(Intent.createChooser(share, getString(R.string.share_chooser_title)))
    }

    private fun loadUrlFromField() {
        val raw = urlField.text.toString().trim()
        if (raw.isEmpty()) return
        val url = normalizeUrl(raw)
        if (url != raw) urlField.setText(url) // skip the layout pass when input already had a scheme
        webView.loadUrl(url)
    }

    /** Pure URL scheme normalization; shared with the upcoming passkey bridge (COK-1.3). */
    private fun normalizeUrl(raw: String): String = when {
        URLUtil.isNetworkUrl(raw) -> raw   // already http(s)
        raw.contains("://") -> raw         // some other scheme — don't double-prefix
        else -> "https://$raw"             // bare input — assume https
    }

    companion object {
        private const val TAG = "CookieExtractor"
    }
}
