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
import android.widget.Switch
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
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import org.json.JSONArray
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

    // COK-24: /api/v1/navigate?wait=load completes when the next onPageFinished fires.
    // Armed and disarmed from the UI thread around the load; cleared on timeout so a late
    // onPageFinished cannot cross-complete a following wait navigation. Residual, accepted:
    // a still-loading PREVIOUS page finishing inside the wait window completes it early.
    @Volatile private var pendingLoad: CompletableFuture<Unit>? = null

    // Held capture: the redirect URL plus the URL of the page that produced it (drives the
    // origin-bound release in onPageFinished). One nullable field so the pair cannot
    // desync. Kept from a capture until the user navigates on purpose (address bar,
    // bookmark, back press), a page settles on a different site, or a new capture replaces
    // it. Never logged or toasted (COK-3 security rule).
    private data class Capture(val url: String, val originUrl: String?)

    private var capture: Capture? = null

    // Back traverses WebView history when there is any; with no history the callback
    // disables itself so the system handles back, which lets Android 16+ show the
    // predictive back-to-home animation instead of swallowing the gesture (COK-25).
    // doUpdateVisitedHistory keeps isEnabled in step with the history stack.
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            setCaptured(null)
            webView.goBack()
        }
    }

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
        val overflowButton: ImageButton = findViewById(R.id.overflow_btn)

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
        overflowButton.setOnClickListener { showOverflowMenu() }

        // Back traverses history via backCallback above (COK-7 capture release included).
        onBackPressedDispatcher.addCallback(this, backCallback)

        // Cold start: load the default URL so the address bar and WebView stay in sync.
        if (savedInstanceState == null) {
            urlField.setText(getString(R.string.default_url))
            commitUrl(getString(R.string.default_url))
        } else {
            // Restore the WebView history (page content itself reloads) and any capture:
            // the authorization code is one-time and must survive recreates not covered by
            // configChanges (fontScale/density/locale/process death).
            webView.restoreState(savedInstanceState)
            // restoreState does not fire navigation callbacks; sync the back callback now
            // or back would exit the app until the restored page commits (COK-25 review).
            backCallback.isEnabled = webView.canGoBack()
            savedInstanceState.getString(STATE_ENTRY_URL)?.let { entryUrl = it }
            savedInstanceState.getString(STATE_CAPTURED_REDIRECT)?.let { setCaptured(it, notify = false) }
        }
        // Fresh starts too (a notification tap on a dead app creates the activity with no
        // saved state); the extra is consumed on first handling so recreates stay put.
        handleNavigateExtra(intent)
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

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            // Fires on new navigations AND on back/forward, so the back callback's enabled
            // state tracks the real history stack (COK-25 predictive back).
            val canGoBack = view.canGoBack()
            if (backCallback.isEnabled != canGoBack) backCallback.isEnabled = canGoBack
            super.doUpdateVisitedHistory(view, url, isReload)
        }

        override fun onPageFinished(view: WebView, url: String) {
            pendingLoad?.complete(Unit)
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
            // Debug builds only (COK-19): a release build must not ship session cookies
            // into logcat, where they ride along any bug report a user attaches.
            if (BuildConfig.DEBUG) Log.i(TAG, "Cookies for ${webView.url}:\n$cookies")
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
        if (request.path == Api.PREFIX || request.path.startsWith(Api.PREFIX + "/")) return routeApi(request)
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
     * Versioned agent SPI (COK-24) on the same server and token as the legacy endpoints.
     * JSON everywhere with one error model; Api.kt owns routing and the pure contracts.
     * /tap and /screenshot are deliberately absent here: they are UI simulation, not the
     * agent contract.
     */
    private fun routeApi(request: DebugHttp.Request): DebugChannelServer.Response =
        when (val parsed = Api.parse(request.path, request.query)) {
            is Api.Parse.Err -> apiJson(parsed.status, Api.errorJson(parsed.code, parsed.message))
            is Api.Parse.Ok -> when (val outcome = executeApi(parsed.command)) {
                is Api.Outcome.Err -> apiJson(outcome.status, Api.errorJson(outcome.code, outcome.message))
                is Api.Outcome.Ok -> apiJson(outcome.status, outcome.json)
            }
        }

    private fun apiJson(status: Int, body: String) = DebugChannelServer.Response(
        status,
        when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            422 -> "Unprocessable Entity"
            503 -> "Service Unavailable"
            504 -> "Gateway Timeout"
            else -> "Internal Server Error"
        },
        "application/json; charset=utf-8",
        (body + "\n").toByteArray(Charsets.UTF_8),
    )

    private fun unavailable() =
        Api.Outcome.Err(503, "unavailable", "app not reachable on the UI thread (timeout or finishing)")

    private fun executeApi(cmd: Api.Command): Api.Outcome {
        return when (cmd) {
        Api.Command.Info -> Api.Outcome.Ok(200, Api.infoJson(BuildConfig.VERSION_NAME))
        Api.Command.Status ->
            withUi<String>(3000) { f ->
                f.complete(
                    JSONObject()
                        .put("url", webView.url ?: JSONObject.NULL)
                        .put("title", webView.title ?: JSONObject.NULL)
                        .put("entry_url", entryUrl ?: JSONObject.NULL)
                        .put("capture_held", capture != null)
                        .put("api_level", Api.API_LEVEL)
                        .toString()
                )
            }?.let { Api.Outcome.Ok(200, it) } ?: unavailable()
        is Api.Command.Navigate -> {
            val normalized = normalizeUrl(cmd.url)
            if (!URLUtil.isNetworkUrl(normalized)) {
                return Api.Outcome.Err(400, "bad_url", "only http(s) urls")
            }
            val signal = if (cmd.waitForLoad) CompletableFuture<Unit>() else null
            if (!withUiSync(3000) {
                    // arm in the same UI transaction that starts the load: onPageFinished is
                    // delivered on this thread, so no finish can slip in before the arm
                    if (signal != null) pendingLoad = signal
                    loadUrl(normalized)
                }) return unavailable()
            if (signal == null) return Api.Outcome.Ok(200, "{\"ok\":true,\"waited\":false}")
            val loaded = try {
                signal.get(NAV_WAIT_LOAD_MS, TimeUnit.MILLISECONDS)
                true
            } catch (e: TimeoutException) {
                if (pendingLoad === signal) pendingLoad = null
                false
            }
            if (loaded) Api.Outcome.Ok(200, "{\"ok\":true,\"waited\":true}")
            else Api.Outcome.Err(504, "load_timeout", "page did not finish loading within ${NAV_WAIT_LOAD_MS / 1000}s")
        }
        Api.Command.Text -> {
            val encoded = withUi<String>(5000) { f ->
                webView.evaluateJavascript("(document.body && document.body.innerText) || ''") { r -> f.complete(r) }
            } ?: return unavailable()
            Api.Outcome.Ok(200, "{\"text\":${Api.json(DebugHttp.jsonUnescape(encoded) ?: encoded)}}")
        }
        Api.Command.Cookies ->
            withUi<String>(3000) { f ->
                val url = webView.url
                val cookies = url?.let { CookieManager.getInstance().getCookie(it) }
                f.complete(
                    JSONObject()
                        .put("url", url ?: JSONObject.NULL)
                        .put("cookies", cookies ?: JSONObject.NULL)
                        .toString()
                )
            }?.let { Api.Outcome.Ok(200, it) } ?: unavailable()
        Api.Command.Capture ->
            withUi<Api.Outcome>(3000) { f ->
                val held = capture?.url
                f.complete(
                    if (held == null) Api.Outcome.Err(404, "no_capture", "no redirect captured")
                    else Api.Outcome.Ok(200, "{\"url\":${Api.json(held)}}")
                )
            } ?: unavailable()
        Api.Command.Clear ->
            if (withUiSync(3000) { clearSessionAndReload() }) Api.Outcome.Ok(200, "{\"ok\":true}")
            else unavailable()
        Api.Command.Bookmarks ->
            Api.Outcome.Ok(
                200,
                JSONObject()
                    .put("bookmarks", JSONArray(repo.load().map { JSONObject().put("title", it.title).put("url", it.url) }))
                    .toString(),
            )
        is Api.Command.BookmarkAdd -> {
            val normalized = normalizeUrl(cmd.url)
            if (!isLoadableUrl(normalized)) {
                return Api.Outcome.Err(400, "bad_url", "scheme not loadable by the WebView")
            }
            val host = Uri.parse(normalized).host.orEmpty().ifBlank { normalized }
            if (!withUiSync(3000) { repo.add(Bookmark(cmd.title ?: host, normalized)) }) return unavailable()
            Api.Outcome.Ok(200, "{\"ok\":true}")
        }
        is Api.Command.BookmarkDelete -> {
            // normalize like add does, or delete-by-bare-host silently misses
            if (!withUiSync(3000) { repo.remove(Bookmark("", normalizeUrl(cmd.url))) }) return unavailable()
            Api.Outcome.Ok(200, "{\"ok\":true}")
        }
        Api.Command.Template -> {
            val stored = shareTemplateRepo.load()
            Api.Outcome.Ok(
                200,
                JSONObject()
                    .put("template", stored ?: JSONObject.NULL)
                    .put("using_default", stored == null)
                    .toString(),
            )
        }
        is Api.Command.TemplateSet -> {
            val trimmed = cmd.template.trim()
            if (!withUiSync(3000) {
                    if (trimmed.isEmpty()) shareTemplateRepo.clear() else shareTemplateRepo.save(trimmed)
                }) return unavailable()
            Api.Outcome.Ok(200, if (trimmed.isEmpty()) "{\"ok\":true,\"cleared\":true}" else "{\"ok\":true,\"cleared\":false}")
        }
        is Api.Command.Console -> {
            val channel = debugChannel ?: return unavailable()
            Api.Outcome.Ok(200, JSONObject().put("lines", JSONArray(channel.console.latest(cmd.lines))).toString())
        }
        is Api.Command.Fill -> {
            val encoded = withUi<String>(5000) { f ->
                webView.evaluateJavascript(Api.fillScript(cmd.selector, cmd.value)) { r -> f.complete(r) }
            } ?: return unavailable()
            when (DebugHttp.jsonUnescape(encoded) ?: encoded) {
                "ok" -> Api.Outcome.Ok(200, "{\"result\":\"ok\"}")
                "not-found" -> Api.Outcome.Err(404, "not_found", "no element matched the selector")
                "unsupported" -> Api.Outcome.Err(422, "unsupported", "matched element is not an input/textarea")
                else -> Api.Outcome.Err(500, "unexpected", "fill returned an unknown result")
            }
        }
        is Api.Command.Submit -> {
            val encoded = withUi<String>(5000) { f ->
                webView.evaluateJavascript(Api.submitScript(cmd.selector)) { r -> f.complete(r) }
            } ?: return unavailable()
            when (DebugHttp.jsonUnescape(encoded) ?: encoded) {
                "ok" -> Api.Outcome.Ok(200, "{\"result\":\"ok\"}")
                "not-found" -> Api.Outcome.Err(404, "not_found", "no element matched the selector")
                else -> Api.Outcome.Err(500, "unexpected", "submit returned an unknown result")
            }
        }
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

    /**
     * Monitor configuration dialog (COK-26): everything about the inbound alert source is
     * user-configurable (state-document URL, bearer token, poll period, enable). Check now
     * runs one cycle immediately without scheduling; Save persists and (re)schedules or
     * cancels the JobScheduler job, requesting POST_NOTIFICATIONS when enabling on 33+.
     */
    private fun showMonitorDialog() {
        val sheet = layoutInflater.inflate(R.layout.dialog_monitor, null)
        val repo = MonitorRepository(this)
        val urlInput: EditText = sheet.findViewById(R.id.monitor_url)
        val tokenInput: EditText = sheet.findViewById(R.id.monitor_token)
        val periodInput: EditText = sheet.findViewById(R.id.monitor_period)
        val enabledSwitch: Switch = sheet.findViewById(R.id.monitor_enabled)
        val checkButton: Button = sheet.findViewById(R.id.monitor_check)
        val statusView: TextView = sheet.findViewById(R.id.monitor_status)
        urlInput.setText(repo.gatewayUrl.orEmpty())
        tokenInput.setText(repo.token.orEmpty())
        periodInput.setText(repo.periodMinutes.toString())
        enabledSwitch.isChecked = repo.enabled
        statusView.text = getString(
            R.string.monitor_status_last_fmt,
            repo.lastResult ?: getString(R.string.monitor_status_never),
        )
        checkButton.setOnClickListener {
            // transient probe of what is on screen: nothing persists, so Cancel still
            // cancels edits and the scheduled job keeps its own configuration until Save
            val url = urlInput.text.toString().trim()
            val token = tokenInput.text.toString().trim()
            checkButton.isEnabled = false
            Thread {
                val status = MonitorPoller.runOnce(applicationContext, url, token)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    checkButton.isEnabled = true
                    statusView.text = getString(R.string.monitor_status_last_fmt, status)
                }
            }.start()
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.monitor_title)
            .setView(sheet)
            .setPositiveButton(R.string.monitor_save) { _, _ ->
                persistMonitorFields(repo, urlInput, tokenInput, periodInput, enabledSwitch)
                if (repo.enabled) {
                    // request on every enable: safe to repeat, and it must not be skipped
                    // just because the switch was already on from a Check-now persist
                    if (Build.VERSION.SDK_INT >= 33) {
                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 26001)
                    }
                    MonitorPoller.schedule(this, repo.periodMinutes)
                } else {
                    MonitorPoller.cancel(this)
                }
            }
            .setNegativeButton(R.string.monitor_cancel, null)
            .create()
        showTracked(dialog)
    }

    private fun persistMonitorFields(
        repo: MonitorRepository,
        url: EditText,
        token: EditText,
        period: EditText,
        enabled: Switch,
    ) {
        repo.gatewayUrl = url.text.toString().trim()
        repo.token = token.text.toString().trim()
        repo.periodMinutes =
            period.text.toString().toIntOrNull() ?: MonitorRepository.DEFAULT_PERIOD_MINUTES
        repo.enabled = enabled.isChecked
    }

    /** A monitor notification was tapped: navigate to the alerted service. */
    private fun handleNavigateExtra(intent: Intent?) {
        val url = intent?.getStringExtra(MonitorPoller.EXTRA_NAVIGATE_URL) ?: return
        // consume it: recreates (rotation, fontScale) re-deliver the same intent, and the
        // activity must not yank the WebView back to the alerted URL every time
        intent.removeExtra(MonitorPoller.EXTRA_NAVIGATE_URL)
        val normalized = normalizeUrl(url)
        if (URLUtil.isNetworkUrl(normalized)) commitUrl(normalized)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNavigateExtra(intent)
    }

    /**
     * Overflow menu for every toolbar action except Go: Bookmarks, Developer options,
     * Session monitor, Clear session. A tracked dialog instead of a PopupMenu: a popup is
     * an untracked window the debug channel's dialog-aware /tap and /screenshot cannot
     * see (COK-12), and it leaks on config-change recreates. Labels and handlers are
     * declared as one list so a reorder can never desync them (COK-28 review).
     */
    private fun showOverflowMenu() {
        val entries = listOf(
            R.string.bookmarks_title to { showBookmarks() },
            R.string.dev_title to { showDeveloperOptions() },
            R.string.monitor_title to { showMonitorDialog() },
            R.string.clear_session_label to { confirmClearSession() },
        )
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.overflow_title)
            .setItems(entries.map { getString(it.first) }.toTypedArray()) { _, which ->
                entries[which].second()
            }
            .create()
        showTracked(dialog)
    }

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
        private const val NAV_WAIT_LOAD_MS = 10_000L
    }
}
