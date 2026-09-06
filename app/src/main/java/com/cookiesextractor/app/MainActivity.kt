package com.cookiesextractor.app

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Single-screen host.
 *  - COK-1.2: address bar + in-app WebView
 *  - COK-1.4: FAB -> extract cookies via CookieManager (empty-state Toast)
 *  - COK-1.5: shareCookies() fires the ACTION_SEND share sheet
 *  - COK-2: bookmarks (save/load/delete) via a bottom sheet
 *  - COK-3: capture non-http OAuth redirects and share the full redirect URL (COK-16)
 *  - COK-9: clear cookies + WebView storage behind a confirm dialog, then reload
 *  - COK-10: token-gated HTTP debug channel (developer options) for external control
 *  - COK-11: Snackbar notice when an OAuth capture lands (the flow's silent terminal state)
 *  - COK-12: debug /tap and /screenshot reach the topmost open dialog, not just the activity
 *
 * Rotation is handled via android:configChanges in the manifest, so the WebView is not
 * destroyed/recreated on orientation change and the loaded page survives.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlField: EditText
    private lateinit var captureFab: FloatingActionButton
    private val repo by lazy { BookmarksRepository(this) }
    private val shareTemplateRepo by lazy { ShareTemplateRepository(this) }
    private var devDialog: AlertDialog? = null

    // COK-12: every open dialog, last-shown last. Dialogs are separate windows, so the
    // debug channel's /tap and /screenshot must target the topmost one instead of the
    // activity window. showTracked() is the only entry point, so add/remove stay paired.
    private val openDialogs = ArrayDeque<Dialog>()

    // Debug channel (COK-10): null while disabled. Its token is regenerated at every
    // enable and never persisted; the developer dialog is the only place it is shown.
    @Volatile private var debugChannel: DebugChannelServer? = null

    // Held capture: the redirect URL plus the URL of the page that produced it (drives the
    // origin-bound release in onPageFinished). One nullable field so the pair cannot
    // desync. Kept from a capture until the user navigates on purpose (address bar,
    // bookmark, back press), a page settles on a different site, or a new capture replaces
    // it. Never logged or toasted (COK-3 security rule).
    private data class Capture(val url: String, val originUrl: String?)

    private var capture: Capture? = null

    // The URL the address bar last showed because the app put it there; a field holding
    // anything else is an un-submitted draft that onPageFinished must not clobber.
    private var syncedUrl: String? = null

    // The URL the user last entered or loaded on purpose (address bar, bookmark, debug
    // channel), captured before redirects can replace it. This is the URL worth
    // re-entering, unlike the post-redirect webView.url (COK-13).
    private var entryUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        urlField = findViewById(R.id.url_field)
        captureFab = findViewById(R.id.capture_fab)
        val goButton: Button = findViewById(R.id.go_button)
        val shareFab: FloatingActionButton = findViewById(R.id.share_fab)
        val devButton: ImageButton = findViewById(R.id.dev_btn)
        val clearSessionButton: ImageButton = findViewById(R.id.clear_session_btn)
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
        // Long-press opens the share-message template editor instead of sharing (COK-15).
        shareFab.setOnLongClickListener { showTemplateEditor(); true }
        captureFab.setOnClickListener { onShareCapturedRedirect() }
        devButton.setOnClickListener { showDeveloperOptions() }
        clearSessionButton.setOnClickListener { confirmClearSession() }
        bookmarksButton.setOnClickListener { showBookmarks() }

        // Back button traverses WebView history before exiting the app; history traversal
        // is user-purposed navigation, so any held capture is released (COK-7).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    setCaptured(null)
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        // Cold start: load the default URL so the address bar and WebView stay in sync.
        if (savedInstanceState == null) {
            urlField.setText(getString(R.string.default_url))
            commitUrl(getString(R.string.default_url))
        } else {
            // Restore the WebView history (page content itself reloads) and any capture:
            // the authorization code is one-time and must survive recreates not covered by
            // configChanges (fontScale/density/locale/process death).
            webView.restoreState(savedInstanceState)
            savedInstanceState.getString(STATE_ENTRY_URL)?.let { entryUrl = it }
            savedInstanceState.getString(STATE_CAPTURED_REDIRECT)?.let { setCaptured(it, notify = false) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
        // Instance state is shell-dumpable; that narrow exposure of the one-time code is
        // deliberately accepted over losing the capture on a recreate.
        capture?.let { outState.putString(STATE_CAPTURED_REDIRECT, it.url) }
        entryUrl?.let { outState.putString(STATE_ENTRY_URL, it) }
    }

    override fun onDestroy() {
        // Dismiss any open dialog (bookmarks sheet, confirm, developer): each leaks its
        // window on config-change recreates not covered by configChanges
        // (e.g. fontScale/density/locale). Copy first: each dismiss mutates the deque.
        openDialogs.toList().forEach { it.dismiss() }
        debugChannel?.stop()
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

        // COK-10: while the debug channel is enabled, keep the page console in the channel's
        // ring buffer for /console. Console lines are the page's own logging and can echo
        // page data, so nothing is recorded while the channel is off.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                val channel = debugChannel ?: return false
                channel.console.add(
                    "[${msg.messageLevel()}] ${msg.message()} @ ${msg.sourceId()}:${msg.lineNumber()}"
                )
                return true
            }
        }

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

        override fun onPageFinished(view: WebView, url: String) {
            // Keep the address bar honest across link/JS/redirect navigation, but never
            // clobber an un-submitted draft (only sync a field still showing the last
            // app-written value) and never write a non-http URL: an error-page echo of a
            // captured redirect carries the code.
            if (URLUtil.isNetworkUrl(url) && !urlField.hasFocus() &&
                urlField.text.toString() == (syncedUrl ?: "")
            ) {
                urlField.setText(url)
                syncedUrl = url
            }
            // Only a settled network page can release or rebind a capture: Chromium also
            // posts onPageFinished(failingUrl) right after a blocked or failed non-http
            // load, and a host-bearing captured URL (myapp://callback) must not release
            // itself.
            if (!URLUtil.isNetworkUrl(url)) return
            val held = capture ?: return
            when {
                RedirectCapture.shouldReleaseCapture(url, held.originUrl) -> setCaptured(null)
                held.originUrl == null ->
                    // Bind a restored/unknown-origin capture to the first page that settles.
                    capture = held.copy(originUrl = url)
            }
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
     * Shares the last captured OAuth redirect (COK-3). The payload is the raw redirect
     * URL, not parsed parameters: a URL is one space-free line, so it survives relays
     * that strip or rejoin newlines (2026-09-06 incident: newline-separated params
     * arrived glued and the code exchange failed). The capture stays in place so the
     * FAB is re-clickable until the next navigation clears it.
     */
    private fun onShareCapturedRedirect() {
        val captured = capture?.url ?: return
        shareViaChooser(captured, R.string.share_tokens_preamble, R.string.share_tokens_chooser_title)
    }

    /**
     * Single writer for the capture state; keeps the field and the FAB visibility in
     * lock-step. [notify] is false on restore-from-state, where a held capture is old news.
     */
    private fun setCaptured(url: String?, notify: Boolean = true) {
        val isNewCapture = notify && url != null && capture?.url != url
        capture = url?.let { Capture(it, webView.url) }
        captureFab.visibility = if (url != null) View.VISIBLE else View.GONE
        if (isNewCapture) showCaptureNotice()
    }

    /**
     * End-of-flow signal (COK-11): a captured redirect leaves the page frozen on the last
     * committed screen, which reads as a stall; this says the opposite. Static text only:
     * the captured URL (the code) never appears here. Accepted edge: while a dialog is
     * open the notice renders behind that window; the FAB remains as the durable signal.
     */
    private fun showCaptureNotice() {
        Snackbar.make(webView, R.string.capture_notice, Snackbar.LENGTH_LONG)
            .setAction(R.string.capture_notice_share) { onShareCapturedRedirect() }
            .show()
    }

    /** All programmatic loadUrl navigation funnels here so the capture is released exactly once. */
    private fun navigate(url: String) {
        setCaptured(null)
        webView.loadUrl(url)
    }

    /**
     * Placeholder values for the share template (COK-15): the page the WebView is on when
     * the user shares. Both flows (cookies, captured tokens) feed the same context, with
     * [payload] carrying the respective text.
     */
    private fun shareContext(payload: String): Map<String, String> {
        val url = webView.url.orEmpty()
        return mapOf(
            ShareTemplate.KEY_PAYLOAD to payload,
            ShareTemplate.KEY_URL to url,
            ShareTemplate.KEY_TITLE to webView.title.orEmpty(),
            ShareTemplate.KEY_HOST to Uri.parse(url).host.orEmpty(),
            // SimpleDateFormat, not java.time: minSdk 24 without coreLibraryDesugaring.
            ShareTemplate.KEY_DATE to SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
        )
    }

    /**
     * Fires the Android share sheet (ACTION_SEND, text/plain) wrapped in Intent.createChooser()
     * per PRD §4.5. A stored template (COK-15) shapes the whole message; without one the
     * preamble string keeps today's output byte-identical.
     */
    private fun shareViaChooser(text: String, @StringRes preambleRes: Int, @StringRes titleRes: Int) {
        val template = shareTemplateRepo.load()
        val message =
            if (template != null) ShareTemplate.render(template, shareContext(text))
            else getString(preambleRes, text)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }
        startActivity(Intent.createChooser(share, getString(titleRes)))
    }

    /**
     * Share-template editor (COK-15), opened by long-pressing the share FAB. The preview
     * re-renders on every keystroke from the page's context (frozen at dialog open) with
     * a sample payload, never the real cookies or tokens; saving an empty field clears
     * the template and restores the default share message.
     */
    private fun showTemplateEditor() {
        val sheet = layoutInflater.inflate(R.layout.dialog_template, null)
        val input: EditText = sheet.findViewById(R.id.template_input)
        val preview: TextView = sheet.findViewById(R.id.template_preview)
        input.setText(shareTemplateRepo.load().orEmpty())
        val previewContext = shareContext(getString(R.string.template_preview_payload))
        fun refreshPreview() {
            val template = input.text.toString()
            preview.text =
                if (template.isBlank()) getString(R.string.template_preview_default)
                else ShareTemplate.render(template, previewContext)
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refreshPreview()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        refreshPreview()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.template_editor_title)
            .setMessage(getString(R.string.template_legend_fmt, ShareTemplate.PLACEHOLDERS.joinToString(" ") { "{$it}" }))
            .setView(sheet)
            .setPositiveButton(R.string.template_save) { _, _ ->
                val template = input.text.toString().trim()
                if (template.isEmpty()) {
                    shareTemplateRepo.clear()
                    Toast.makeText(this, R.string.toast_template_cleared, Toast.LENGTH_SHORT).show()
                } else {
                    shareTemplateRepo.save(template)
                    Toast.makeText(this, R.string.toast_template_saved, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.template_cancel, null)
            .setNeutralButton(R.string.template_reset) { _, _ ->
                shareTemplateRepo.clear()
                Toast.makeText(this, R.string.toast_template_cleared, Toast.LENGTH_SHORT).show()
            }
            .create()
        showTracked(dialog)
    }

    private fun loadUrlFromField() {
        val raw = urlField.text.toString().trim()
        if (raw.isEmpty()) return
        val url = normalizeUrl(raw)
        if (!isLoadableUrl(url)) {
            // The WebView cannot load this scheme; letting it fail would route the user's
            // own input into the capture path and mislabel it as OAuth tokens.
            Toast.makeText(this, R.string.toast_scheme_not_loadable, Toast.LENGTH_SHORT).show()
            return
        }
        if (url != raw) urlField.setText(url) // skip the layout pass when input already had a scheme
        commitUrl(url)
    }

    /**
     * Single writer for "the app deliberately went to url": address-bar sync, entry-URL
     * memory, focus release, and the capture-releasing load. All three entry funnels
     * (cold start, address bar, bookmark/debug load) go through here so the
     * syncedUrl/entryUrl pair cannot desync (COK-13).
     */
    private fun commitUrl(url: String) {
        syncedUrl = url
        entryUrl = url
        urlField.clearFocus() // so onPageFinished can resync the bar after redirects
        navigate(url)
    }

    /** Pure URL scheme normalization. */
    private fun normalizeUrl(raw: String): String = when {
        URLUtil.isNetworkUrl(raw) -> raw   // already http(s)
        raw.contains("://") -> raw         // some other scheme: don't double-prefix
        else -> "https://$raw"             // bare input: assume https
    }

    /**
     * True when a normalized [url] can be loaded as a page. Capture-schemes must be rejected
     * before load: their failure would route the URL into onReceivedError's capture path and
     * mislabel it as OAuth tokens. Shared by the address bar and the bookmark Add row.
     */
    private fun isLoadableUrl(url: String): Boolean {
        val scheme = Uri.parse(url).scheme ?: return true
        return !RedirectCapture.shouldCaptureScheme(scheme)
    }

    /**
     * Confirm-then-wipe of all WebView session state (COK-9). A wedged IdP session (server-side
     * state that no in-page action can advance) is only cleared by deleting everything; the
     * reload makes the fresh state visible immediately. Never logs or toasts any wiped value.
     */
    private fun confirmClearSession() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_session_title)
            .setMessage(R.string.clear_session_message)
            .setPositiveButton(R.string.clear_session_confirm) { _, _ -> clearSessionAndReload() }
            .setNegativeButton(R.string.clear_session_cancel, null)
            .create()
            .also { showTracked(it) }
    }

    private fun clearSessionAndReload() {
        // Storage is wiped synchronously (so the wipe lands even if the activity dies before
        // the cookie callback); page JS still alive in that window could re-persist, which
        // the reload right after makes visible.
        WebStorage.getInstance().deleteAllData()
        val url = currentNetworkUrl()
        // removeAllCookies covers session cookies too (they are a subset); the callback can
        // arrive after onDestroy, so touch the UI only while the activity lives.
        CookieManager.getInstance().removeAllCookies {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setCaptured(null) // the wipe starts every login over, a held capture included
                Toast.makeText(this, R.string.toast_session_cleared, Toast.LENGTH_SHORT).show()
                // Reload only if the user did not navigate elsewhere while the wipe ran.
                if (url != null && webView.url == url) navigate(url)
            }
        }
    }

    /** The WebView's current URL when it is a loadable network page, else null. */
    private fun currentNetworkUrl(): String? =
        webView.url?.takeIf { URLUtil.isNetworkUrl(it) }

    /** Shows [dialog] tracked in [openDialogs]; [onDismiss] runs first on dismissal. */
    private fun showTracked(dialog: Dialog, onDismiss: () -> Unit = {}) {
        dialog.setOnDismissListener {
            onDismiss()
            openDialogs.remove(dialog)
        }
        dialog.show()
        openDialogs.addLast(dialog)
    }

    // ---- Debug channel (COK-10) ----

    /** Developer dialog: channel state, the URL+token to use, and the enable/disable toggle. */
    private fun showDeveloperOptions() {
        devDialog?.dismiss()
        val channel = debugChannel
        val message =
            if (channel != null) getString(R.string.dev_status_on_fmt, lanIp() ?: "?", channel.boundPort, channel.token)
            else getString(R.string.dev_status_off)
        devDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dev_title)
            .setMessage(message)
            .setPositiveButton(if (channel != null) R.string.dev_disable else R.string.dev_enable) { _, _ ->
                if (channel != null) disableDebugChannel() else enableDebugChannel()
            }
            .setNegativeButton(R.string.dev_close, null)
            .create()
            .also { showTracked(it) { devDialog = null } }
    }

    private fun enableDebugChannel() {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        val channel = DebugChannelServer(DEBUG_PORT, token, lanIp(), ::routeDebugRequest)
        try {
            channel.start()
        } catch (e: IOException) {
            Toast.makeText(this, R.string.dev_port_busy, Toast.LENGTH_SHORT).show()
            return
        }
        debugChannel = channel
        showDeveloperOptions() // re-render with the URL + token to use
    }

    private fun disableDebugChannel() {
        debugChannel?.stop()
        debugChannel = null
    }

    /**
     * Maps one authorized debug request to a response (COK-10). The parser only produces
     * GETs. UI-touching endpoints bridge to the main thread with a timeout; a timeout or a
     * dead activity yields 503. This is a developer channel: /capture hands out the captured
     * OAuth parameters and /screenshot the rendered screen, both gated behind the token.
     */
    private fun routeDebugRequest(request: DebugHttp.Request): DebugChannelServer.Response {
        return when (request.path) {
            "/status" -> withUi<String>(3000) { f -> f.complete(statusJson()) }
                ?.let { DebugChannelServer.Response.json(it) } ?: serviceUnavailable()
            "/navigate" -> {
                val url = normalizeUrl(request.query["url"].orEmpty())
                if (url.isEmpty()) {
                    return DebugChannelServer.Response.text(400, "Bad Request", "missing url\r\n")
                }
                if (!URLUtil.isNetworkUrl(url)) {
                    // network-only on purpose: file:// and friends are not a navigation
                    // target for a remote pilot, and a capture-scheme URL would land in the
                    // capture path and overwrite a genuine held code.
                    return DebugChannelServer.Response.text(400, "Bad Request", "only http(s) urls\r\n")
                }
                if (!withUiSync(3000) { loadUrl(url) }) return serviceUnavailable()
                DebugChannelServer.Response.ok("navigating\r\n")
            }
            "/text" -> {
                val encoded = withUi<String>(5000) { f ->
                    webView.evaluateJavascript("(document.body && document.body.innerText) || ''") { r ->
                        f.complete(r)
                    }
                } ?: return serviceUnavailable()
                DebugChannelServer.Response.ok(DebugHttp.jsonUnescape(encoded) ?: encoded)
            }
            "/screenshot" -> {
                val jpeg = withUi<ByteArray>(8000) { f -> completeWithScreenshot(f) } ?: return serviceUnavailable()
                DebugChannelServer.Response(200, "OK", "image/jpeg", jpeg)
            }
            "/capture" -> {
                val held = withUi<String>(3000) { f -> f.complete(capture?.url) } ?: return serviceUnavailable()
                if (held == null) return DebugChannelServer.Response.text(404, "Not Found", "no capture\r\n")
                DebugChannelServer.Response.ok(RedirectCapture.shareText(held) + "\r\n")
            }
            "/clear" -> {
                if (!withUiSync(3000) { clearSessionAndReload() }) return serviceUnavailable()
                DebugChannelServer.Response.ok("clearing\r\n")
            }
            "/console" -> {
                val n = request.query["n"]?.toIntOrNull()?.coerceIn(1, DebugChannelServer.MAX_CONSOLE_LINES) ?: 50
                val channel = debugChannel ?: return serviceUnavailable()
                DebugChannelServer.Response.ok(channel.console.latest(n).joinToString("\n") + "\n")
            }
            "/tap" -> {
                val x = request.query["x"]?.toFloatOrNull()
                val y = request.query["y"]?.toFloatOrNull()
                if (x == null || y == null) {
                    return DebugChannelServer.Response.text(400, "Bad Request", "x and y required\r\n")
                }
                if (!withUiSync(3000) { dispatchTap(x, y) }) return serviceUnavailable()
                DebugChannelServer.Response.ok("tapped\r\n")
            }
            else -> DebugChannelServer.Response.text(404, "Not Found", "unknown endpoint\r\n")
        }
    }

    /**
     * Runs [block] on the UI thread; the block owns its [CompletableFuture] so both sync
     * values and async WebView results work. Null on timeout, dead activity, or throw.
     */
    private fun <T> withUi(timeoutMs: Long, block: (CompletableFuture<T?>) -> Unit): T? {
        val future = CompletableFuture<T?>()
        runOnUiThread {
            if (isFinishing || isDestroyed) future.complete(null)
            else try {
                block(future)
            } catch (e: Exception) {
                future.complete(null)
            }
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            null
        }
    }

    /** Fire-and-forget UI action; false when it did not run cleanly within [timeoutMs]. */
    private fun withUiSync(timeoutMs: Long, action: () -> Unit): Boolean =
        withUi<Boolean>(timeoutMs) { f ->
            f.complete(runCatching(action)
                .onFailure { Log.w(TAG, "debug ui action failed: ${it.javaClass.simpleName}") }
                .isSuccess)
        } == true

    private fun serviceUnavailable(): DebugChannelServer.Response =
        DebugChannelServer.Response.text(503, "Service Unavailable", "ui unavailable\r\n")

    /** Must run on the UI thread (called through [withUi]). */
    private fun statusJson(): String = JSONObject().apply {
        put("url", webView.url ?: "")
        put("title", webView.title ?: "")
        put("capture_held", capture != null)
        put("debug_port", debugChannel?.boundPort ?: 0)
    }.toString()

    /** Renders the activity into a JPEG and completes [future] with it (or null). */
    private fun completeWithScreenshot(future: CompletableFuture<ByteArray?>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            future.complete(null) // PixelCopy needs API 26; debug builds target newer devices
            return
        }
        // Copy the topmost dialog when one is showing: /tap can reach dialogs, so
        // /screenshot must show them too (dialogs are separate windows). A dialog caught
        // before its first layout pass has no size yet; fall back to the activity window.
        val targetWindow = openDialogs.lastOrNull { it.isShowing }?.window
            ?.takeIf { it.decorView.width > 0 } ?: window
        val decor = targetWindow.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(targetWindow, bitmap, { result ->
            val bytes =
                if (result == PixelCopy.SUCCESS) {
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
                    out.toByteArray()
                } else {
                    null
                }
            bitmap.recycle()
            future.complete(bytes)
        }, Handler(Looper.getMainLooper()))
    }

    /**
     * Dispatches on the topmost open dialog when one is showing (dialogs are separate
     * windows the activity never sees), else on the activity window. Coordinates arrive in
     * screen space; a wrap-content dialog window's decor starts at its own on-screen
     * offset, so the tap is translated into that window's local space first.
     */
    private fun dispatchTap(x: Float, y: Float) {
        // The width guard mirrors /screenshot: a dialog caught before its first layout
        // must not steal coordinates that came from an activity-window screenshot.
        val dialogDecor = openDialogs.lastOrNull { it.isShowing }?.window?.decorView
            ?.takeIf { it.width > 0 }
        val (target, lx, ly) = if (dialogDecor != null) {
            val origin = IntArray(2)
            dialogDecor.getLocationOnScreen(origin)
            Triple(dialogDecor, x - origin[0], y - origin[1])
        } else {
            Triple(window.decorView, x, y)
        }
        val now = SystemClock.uptimeMillis()
        listOf(
            MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, lx, ly, 0),
            MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, lx, ly, 0),
        ).forEach {
            target.dispatchTouchEvent(it)
            it.recycle()
        }
    }

    /** First site-local IPv4 of this device, for the URL shown in the developer dialog. */
    private fun lanIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    /** Shows the bookmarks bottom sheet: add by URL, tap to load, delete. */
    private fun showBookmarks() {
        val dialog = BottomSheetDialog(this)
        val sheet = layoutInflater.inflate(R.layout.dialog_bookmarks, null)
        val list: LinearLayout = sheet.findViewById(R.id.bookmarks_list)
        val empty: TextView = sheet.findViewById(R.id.bookmarks_empty)
        val urlInput: EditText = sheet.findViewById(R.id.bm_url_input)
        val addButton: Button = sheet.findViewById(R.id.bm_add)

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

        // The entry URL (pre-redirect) of the current flow, editable for a custom address;
        // normalizeUrl keeps bare input usable, repo.add upserts by URL.
        urlInput.setText(entryUrl ?: currentNetworkUrl() ?: "")
        addButton.setOnClickListener {
            val raw = urlInput.text.toString().trim()
            if (raw.isEmpty()) {
                Toast.makeText(this, R.string.toast_bm_empty_input, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val url = normalizeUrl(raw)
            if (!isLoadableUrl(url)) {
                // Same guard as the address bar: a non-loadable scheme would land in the
                // capture path on load and mislabel itself as captured tokens.
                Toast.makeText(this, R.string.toast_scheme_not_loadable, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Page title when this IS the current page (nothing redirected in between);
            // behind redirects the host is the honest label for an entry URL. Chromium
            // canonicalizes an empty path to a trailing slash, so compare tolerantly.
            val host = Uri.parse(url).host.orEmpty().ifBlank { url }
            val onCurrentPage = webView.url?.removeSuffix("/") == url.removeSuffix("/")
            val title = if (onCurrentPage) webView.title?.takeIf { it.isNotBlank() } ?: host else host
            render(repo.add(Bookmark(title, url)))
            urlInput.setText(url)
        }

        dialog.setContentView(sheet)
        // The EditText row makes the sheet open in the collapsed peek state; force it
        // expanded or the add row sits below the fold (observed on device).
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        render(repo.load())
        showTracked(dialog)
    }

    private fun loadUrl(url: String) {
        urlField.setText(url)
        commitUrl(url)
    }

    companion object {
        private const val TAG = "CookieExtractor"
        private const val DEBUG_PORT = 8777
        private const val STATE_CAPTURED_REDIRECT = "captured_redirect"
        private const val STATE_ENTRY_URL = "entry_url"
    }
}
