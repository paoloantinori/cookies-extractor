package com.cookiesextractor.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * Single-screen host.
 *  - COK-1.2: address bar + in-app WebView
 *  - COK-1.4: FAB -> extract cookies via CookieManager (empty-state Toast)
 *  - COK-1.5: shareCookies() fires the ACTION_SEND share sheet
 *  - COK-2: bookmarks (save/load/delete) via a bottom sheet
 *  - COK-3: capture non-http OAuth redirects and share their token parameters
 *
 * Rotation is handled via android:configChanges in the manifest, so the WebView is not
 * destroyed/recreated on orientation change and the loaded page survives.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlField: EditText
    private lateinit var captureFab: FloatingActionButton
    private val repo by lazy { BookmarksRepository(this) }
    private var bookmarksDialog: BottomSheetDialog? = null

    // Non-null from a captured redirect until the user navigates on purpose (address bar,
    // bookmark) or a new capture replaces it. Never cleared by page-side navigation: IdP
    // pages routinely follow the blocked redirect with a fallback load that must not
    // destroy the one-time code. Never logged or toasted (COK-3 security rule).
    private var lastCapturedRedirect: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        urlField = findViewById(R.id.url_field)
        captureFab = findViewById(R.id.capture_fab)
        val goButton: Button = findViewById(R.id.go_button)
        val shareFab: FloatingActionButton = findViewById(R.id.share_fab)
        val bookmarksButton: ImageButton = findViewById(R.id.bookmarks_btn)

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

        shareFab.setOnClickListener { onExtractCookies() }
        captureFab.setOnClickListener { onShareCapturedRedirect() }
        bookmarksButton.setOnClickListener { showBookmarks() }

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
        } else {
            // Restore the WebView history (page content itself reloads) and any capture:
            // the authorization code is one-time and must survive recreates not covered by
            // configChanges (fontScale/density/locale/process death).
            webView.restoreState(savedInstanceState)
            savedInstanceState.getString(STATE_CAPTURED_REDIRECT)?.let { setCaptured(it) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
        // Instance state is shell-dumpable; that narrow exposure of the one-time code is
        // deliberately accepted over losing the capture on a recreate.
        lastCapturedRedirect?.let { outState.putString(STATE_CAPTURED_REDIRECT, it) }
    }

    override fun onDestroy() {
        // Dismiss the bookmarks sheet if open — prevents a window leak on config-change
        // recreates not covered by configChanges (e.g. fontScale/density/locale).
        bookmarksDialog?.dismiss()
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
        webView.webViewClient = CapturingWebViewClient() // keep navigation in-app (PRD §4.2/§4.3)

        // Accept and keep cookies so CookieManager can read them back after login. Third-party
        // cookies are enabled so the WebView stores them and will send the ones scoped to the
        // loaded domain when getCookie(url) is called. Note: getCookie returns only cookies
        // scoped to the loaded URL — a cookie set on a fully separate domain (e.g. an IdP)
        // won't appear here; cross-domain aggregation is future work.
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)
    }

    /**
     * Keeps navigation in-app and captures non-http redirects (COK-3). After a manual OAuth
     * login the IdP redirects to a scheme the WebView cannot load (typically
     * urn:ietf:wg:oauth:2.0:oob?code=...); blocking the load here prevents the "Web page not
     * available" error page and hands the URL to the capture FAB instead.
     */
    private inner class CapturingWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Chromium fires this for iframe navigations too (e.g. ad-SDK intent://
            // fallbacks); only a main-frame redirect carries the authorization code, and an
            // iframe capture would overwrite the real one.
            if (!request.isForMainFrame) return false
            return captureIfRedirect(request)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            // Fallback for main-frame non-http loads the override path misses. Sub-resource
            // errors are the common case here, so the guard comes before any allocation.
            // Deliberately no navigation recovery: goBack() here races the error-page commit
            // (this callback runs before it in Chromium) and about:blank desyncs the address
            // bar; the stock error page on this rare path is the safer residual (COK-5).
            if (!request.isForMainFrame) return
            captureIfRedirect(request)
        }

        private fun captureIfRedirect(request: WebResourceRequest): Boolean {
            val uri = request.url
            if (!RedirectCapture.shouldCaptureScheme(uri.scheme)) return false
            setCaptured(uri.toString())
            return true
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

    private fun shareCookies(cookies: String) =
        shareViaChooser(cookies, R.string.share_preamble, R.string.share_chooser_title)

    /**
     * Shares the last captured OAuth redirect's parameters (COK-3). The capture stays in
     * place so the FAB is re-clickable until the next navigation clears it.
     */
    private fun onShareCapturedRedirect() {
        val captured = lastCapturedRedirect ?: return
        shareViaChooser(
            RedirectCapture.shareText(captured),
            R.string.share_tokens_preamble,
            R.string.share_tokens_chooser_title
        )
    }

    /** Single writer for the capture state; keeps the field and the FAB visibility in lock-step. */
    private fun setCaptured(url: String?) {
        lastCapturedRedirect = url
        captureFab.visibility = if (url != null) View.VISIBLE else View.GONE
    }

    /**
     * Fires the Android share sheet (ACTION_SEND, text/plain) wrapped in Intent.createChooser()
     * per PRD §4.5.
     */
    private fun shareViaChooser(text: String, @StringRes preambleRes: Int, @StringRes titleRes: Int) {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, getString(preambleRes, text))
        }
        startActivity(Intent.createChooser(share, getString(titleRes)))
    }

    private fun loadUrlFromField() {
        val raw = urlField.text.toString().trim()
        if (raw.isEmpty()) return
        val url = normalizeUrl(raw)
        if (url != raw) urlField.setText(url) // skip the layout pass when input already had a scheme
        setCaptured(null) // user-initiated navigation invalidates any held capture
        webView.loadUrl(url)
    }

    /** Pure URL scheme normalization. */
    private fun normalizeUrl(raw: String): String = when {
        URLUtil.isNetworkUrl(raw) -> raw   // already http(s)
        raw.contains("://") -> raw         // some other scheme — don't double-prefix
        else -> "https://$raw"             // bare input — assume https
    }

    /** Shows the bookmarks bottom sheet: add current page, tap to load, delete. */
    private fun showBookmarks() {
        val dialog = BottomSheetDialog(this)
        val sheet = layoutInflater.inflate(R.layout.dialog_bookmarks, null)
        val list: LinearLayout = sheet.findViewById(R.id.bookmarks_list)
        val empty: TextView = sheet.findViewById(R.id.bookmarks_empty)
        val addCurrent: Button = sheet.findViewById(R.id.add_current)

        fun render(items: List<Bookmark>) {
            list.removeAllViews()
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            items.forEach { bm ->
                val row = layoutInflater.inflate(R.layout.item_bookmark, list, false)
                row.findViewById<TextView>(R.id.bm_title).text = bm.title.ifBlank { bm.url }
                row.findViewById<TextView>(R.id.bm_url).text = bm.url
                row.setOnClickListener { loadUrl(bm.url); dialog.dismiss() }
                row.findViewById<ImageButton>(R.id.bm_delete).setOnClickListener {
                    render(repo.remove(bm))
                }
                list.addView(row)
            }
        }

        addCurrent.setOnClickListener {
            val url = webView.url
            if (url != null && URLUtil.isNetworkUrl(url)) {
                val title = webView.title?.takeIf { it.isNotBlank() } ?: Uri.parse(url).host.orEmpty()
                render(repo.add(Bookmark(title, url)))
            } else {
                Toast.makeText(this, R.string.toast_no_page, Toast.LENGTH_SHORT).show()
            }
        }

        dialog.setOnDismissListener { bookmarksDialog = null }
        dialog.setContentView(sheet)
        render(repo.load())
        bookmarksDialog = dialog
        dialog.show()
    }

    private fun loadUrl(url: String) {
        setCaptured(null) // user-initiated navigation invalidates any held capture
        urlField.setText(url)
        webView.loadUrl(url)
    }

    companion object {
        private const val TAG = "CookieExtractor"
        private const val STATE_CAPTURED_REDIRECT = "captured_redirect"
    }
}
