package app.readly

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import app.readly.input.ArticleFetcher
import app.readly.input.sites.BrowserRead
import app.readly.input.sites.SiteBrowserLoadTracker
import app.readly.input.sites.SiteBrowserPolicy
import app.readly.input.sites.SiteBrowserReadTracker
import app.readly.input.sites.SiteUsage
import app.readly.input.sites.sameReadUrl
import app.readly.playback.ReaderService
import app.readly.text.SourceFormat
import app.readly.text.TextSanitizer
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Legacy manual browser fallback (WebView, no extensions). NOT in any normal
 * UI route since the automatic article engine ([ArticleActivity]) replaced
 * it: shares, pasted links and retries all open the article engine. Kept only
 * so existing installs/tests referencing the class keep resolving; new code
 * must not launch it.
 *
 * Original behavior retained below for reference: a user-invoked browser for
 * a failed link read (opened only from an `Open page` tap, never
 * automatically). Any ordinary website link may open:
 * the owner opens the article, completes any sign-in or verification, then
 * taps `Read page` to hand the rendered article to the existing reader
 * without another network fetch.
 *
 * This is an independent WebView session with its own cookie storage; it
 * cannot guarantee passing publisher challenges or access checks, and it
 * claims no full browser/extension compatibility.
 *
 * Controls: website JavaScript and DOM storage are on (site challenges
 * need them), with the WebView's normal browser UA — never an imported
 * rule UA, extension script, or cookie edit. There is no native bridge
 * ([android.webkit.JavascriptInterface] is never added), no file/content
 * access, no mixed content, no SSL-error bypass (the default handler
 * cancels), no popups, download handlers, or runtime web permissions.
 * Main-frame navigation follows normal eligible HTTPS links, including
 * legitimate cross-domain login flows; unsafe targets are blocked with a
 * reason, never auto-opened elsewhere. `Read page` snapshots only the
 * top-level DOM, bounded before it crosses the bridge, validates the
 * snapshot, callback-time and final URLs against the captured article URL,
 * and runs the same guarded extraction as the native quick reader (exact
 * NYT guard/extractor on main NYT hosts, shared guard plus declarative
 * cleanup elsewhere) plus [TextSanitizer] off the main thread. Stale
 * results (navigation, same-document change, pause/stop or destroy
 * meanwhile) are discarded via [SiteBrowserReadTracker]; load errors
 * survive their finish event via [SiteBrowserLoadTracker] and `Read page`
 * stays disabled until the current navigation is loaded, error-free and
 * extraction-eligible.
 */
class NytBrowserActivity : Activity() {

    companion object {
        const val EXTRA_URL = "nytUrl"

        /** Opens the browser fallback for one exact failed link. */
        fun intent(context: Context, url: String): Intent =
            Intent(context, NytBrowserActivity::class.java).putExtra(EXTRA_URL, url)
    }

    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private lateinit var readButton: Button
    private lateinit var reloadButton: Button
    private lateinit var parseExecutor: ExecutorService
    private val readTracker = SiteBrowserReadTracker(SiteBrowserPolicy::allowsExtraction)
    private val loadTracker = SiteBrowserLoadTracker(
        SiteBrowserPolicy::allowsBrowsing,
        SiteBrowserPolicy::allowsExtraction,
    )
    private var readBusy = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nyt_browser)
        webView = findViewById(R.id.browserWebView)
        statusView = findViewById(R.id.browserStatus)
        readButton = findViewById(R.id.browserReadButton)
        reloadButton = findViewById(R.id.browserReloadButton)
        parseExecutor = Executors.newSingleThreadExecutor { Thread(it, "readly-browser-parse") }

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (!SiteBrowserPolicy.allowsExtraction(url)) {
            showError("Can't open this page: ${SiteBrowserPolicy.blockReason(url)}: $url")
            webView.visibility = android.view.View.GONE
            readButton.isEnabled = false
            reloadButton.isEnabled = false
            return
        }
        // The WebView keeps its normal browser UA so site JS/challenges can
        // run; this is distinct from the native fetch's rule headers, which
        // are never sent here. No bridge is ever added, and no file/content
        // access is granted.
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.setSupportMultipleWindows(false)
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // No DownloadListener, no custom popup handling, no
        // onReceivedSslError override: SSL errors cancel by default.
        // Runtime web permissions (camera, microphone, …) are always denied.
        webView.webViewClient = BrowserClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
                val note = "Blocked: this browser denies camera, microphone and other web permissions."
                Log.w("Readly", note)
                showStatus(note)
            }
        }
        readButton.setOnClickListener { readPage() }
        reloadButton.setOnClickListener { webView.reload() }
        updateReadButton()
        webView.loadUrl(url)
    }

    override fun onPause() {
        // Retire any in-flight read so a background completion can never
        // start speech or an activity; resume restores a usable state.
        readTracker.onPaused()
        setBusy(false)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        readTracker.onResumed()
        setBusy(false)
        restoreStatusFromLoad()
    }

    override fun onStop() {
        readTracker.onStopped()
        setBusy(false)
        super.onStop()
    }

    override fun onDestroy() {
        readTracker.onDestroyed()
        if (::parseExecutor.isInitialized) parseExecutor.shutdownNow()
        if (::webView.isInitialized) {
            try {
                webView.stopLoading()
            } catch (e: Exception) {
                Log.w("Readly", "Browser teardown failed to stop loading: ${e.javaClass.name}: ${e.message}")
            }
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    /** Snapshots the top-level DOM and hands a guarded article to the reader. */
    private fun readPage() {
        val currentUrl = webView.url
        if (loadTracker.isLoading()) {
            showError("Can't read this page yet: the page is still loading (now at ${currentUrl ?: "nothing"}).")
            return
        }
        val read = readTracker.startRead(currentUrl)
        if (read == null) {
            val detail = when {
                currentUrl.isNullOrBlank() -> "there is no page to read"
                SiteBrowserPolicy.blockReasonOrNull(currentUrl) != null ->
                    SiteBrowserPolicy.blockReason(currentUrl)
                else -> "the browser is busy or in the background"
            }
            showError("Can't read this page: $detail (now at ${currentUrl ?: "nothing"}).")
            return
        }
        setBusy(true)
        showStatus(getString(R.string.nyt_browser_reading))
        try {
            webView.evaluateJavascript(SiteBrowserPolicy.SNAPSHOT_JS) { result ->
                if (!readTracker.isCurrent(read)) return@evaluateJavascript
                val callbackUrl = webView.url
                if (!sameReadUrl(read.url, callbackUrl)) {
                    postReadError(read, "Can't read this page: the page changed during reading.")
                    return@evaluateJavascript
                }
                try {
                    parseExecutor.submit { parseSnapshot(read, result, callbackUrl) }
                } catch (e: RejectedExecutionException) {
                    showError("Read page failed: ${e.javaClass.name}: ${e.message}")
                    setBusy(false)
                }
            }
        } catch (e: Exception) {
            showError("Read page failed: ${e.javaClass.name}: ${e.message}")
            setBusy(false)
        }
    }

    /**
     * Decodes, validates, extracts and sanitizes one snapshot on the parse
     * thread: no HTTP refetch, no raw DOM to speech. The current catalog is
     * pinned once per `Read page` here (init may read bundled/cache files,
     * never the network, so it must not run on main). Errors land back on
     * main through [postReadError]; success validates the final URL on main
     * through [postHandoff]. Stale reads are discarded without speaking.
     */
    private fun parseSnapshot(read: BrowserRead, jsResult: String?, callbackUrl: String?) {
        try {
            when (val snap = SiteBrowserPolicy.decodeJsResult(jsResult)) {
                is SiteBrowserPolicy.DomSnapshot.Rejected -> {
                    postReadError(read, snap.reason)
                    return
                }
                is SiteBrowserPolicy.DomSnapshot.Ok -> {
                    if (!readTracker.snapshotBelongs(read, snap.url, callbackUrl)) {
                        postReadError(read, "Can't read this page: the browser left the article.")
                        return
                    }
                    val app = application as? ReadlyApp
                    val snapshot = app?.siteRules?.snapshotForReading()
                    val usage = SiteUsage()
                    val readHiddenText = ReaderService.isHiddenTextOn(this)
                    val doc = try {
                        ArticleFetcher(ArticleFetcher::httpUrlOpener, readHiddenText = readHiddenText)
                            .extractRenderedHtml(snap.html, snap.url, snapshot, usage)
                    } catch (e: Exception) {
                        postReadError(read, e.message ?: "${e.javaClass.name}: (no details)")
                        return
                    }
                    val clean = TextSanitizer.sanitize(doc.text, SourceFormat.HTML, readHiddenText)
                    if (clean.isBlank()) {
                        postReadError(read, "Read page failed: no readable article text was found on this page.")
                        return
                    }
                    SiteBrowserPolicy.handoffTooLargeError(clean.length)?.let { tooLarge ->
                        postReadError(read, tooLarge)
                        return
                    }
                    // The browser sends its normal UA, so there is never a
                    // native-headers notice here — only extraction, cleanup,
                    // and startup-error notices.
                    val startupError = if (snapshot == null) {
                        app?.siteRules?.uiState()?.startupError
                    } else {
                        null
                    }
                    val notice = SiteBrowserPolicy.readingNotice(
                        doc.notice,
                        usage.cleanupApplied,
                        usage.noticeHost(),
                        startupError,
                    )
                    postHandoff(read, clean, doc.title, notice)
                }
            }
        } catch (e: Exception) {
            postReadError(read, "Read page failed: ${e.javaClass.name}: ${e.message}")
        }
    }

    /** Shows a read error on main unless the read went stale meanwhile. */
    private fun postReadError(read: BrowserRead, message: String) {
        runOnUiThread {
            if (!readTracker.isCurrent(read)) return@runOnUiThread
            showError(message)
            setBusy(false)
        }
    }

    /**
     * Hands sanitized text to the reader on main. The final WebView URL is
     * rechecked against [read]; stale handoffs are discarded silently (the
     * navigation or pause already restored the UI), handoff exceptions are
     * surfaced with their real class/message, and background launches never
     * happen because [SiteBrowserReadTracker.finishRead] refuses paused reads.
     */
    private fun postHandoff(read: BrowserRead, clean: String, title: String, notice: String?) {
        runOnUiThread {
            val finalUrl = if (::webView.isInitialized) webView.url else null
            when (val outcome = readTracker.finishRead(read, finalUrl) {
                ReaderService.read(this, clean, title, SourceFormat.PLAIN, notice)
                startActivity(Intent(this, NowPlayingActivity::class.java))
                overridePendingTransition(R.anim.screen_slide_in, R.anim.screen_slide_out)
                finish()
            }) {
                is SiteBrowserReadTracker.HandoffOutcome.Stale -> setBusy(false)
                is SiteBrowserReadTracker.HandoffOutcome.Failed -> {
                    showError(outcome.reason)
                    setBusy(false)
                }
                is SiteBrowserReadTracker.HandoffOutcome.Done -> loadTracker.onReadSucceeded()
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        readBusy = busy
        updateReadButton()
    }

    private fun updateReadButton() {
        if (!::readButton.isInitialized) return
        readButton.isEnabled = !readBusy && loadTracker.canEnableRead()
    }

    /** Restores status text from the load tracker after resume. */
    private fun restoreStatusFromLoad() {
        if (!::statusView.isInitialized || !::webView.isInitialized) return
        val state = loadTracker.snapshot()
        val err = state.error
        when {
            err != null -> {
                if (err.startsWith("blocked:")) {
                    showError(blockedMessage(err.removePrefix("blocked:")))
                } else {
                    showError(err)
                }
            }
            state.loading -> showStatus(getString(R.string.nyt_browser_loading_format, hostOf(state.currentUrl)))
            state.currentUrl != null -> showStatus(getString(R.string.nyt_browser_loaded_format, hostOf(state.currentUrl)))
        }
        updateReadButton()
    }

    private fun blockedMessage(url: String): String =
        getString(R.string.nyt_browser_blocked_format, SiteBrowserPolicy.blockReason(url))

    private fun showStatus(text: String) {
        statusView.setTextColor(getColor(R.color.text3))
        statusView.text = text
    }

    private fun showError(text: String) {
        Log.w("Readly", text)
        statusView.setTextColor(getColor(R.color.error))
        statusView.text = text
    }

    private fun hostOf(url: String?): String {
        if (url.isNullOrBlank()) return "nothing"
        return try {
            URI(url).host ?: url
        } catch (_: Exception) {
            url
        }
    }

    /**
     * Main-frame gate plus loading/error reporting. Subframe loads are
     * untouched (ads/embeds need them); only main-frame navigation is
     * policed, popups are disabled, and SSL errors keep the default
     * cancel behavior.
     */
    private inner class BrowserClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val target = request.url.toString()
            if (SiteBrowserPolicy.allowsBrowsing(target)) return false
            val note = blockedMessage(target)
            Log.w("Readly", note)
            showError(note)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            // `shouldOverrideUrlLoading` doesn't see every navigation (app
            // loadUrl/POST paths), so recheck the observed main-frame target.
            when (loadTracker.onNavigationStarted(url)) {
                is SiteBrowserLoadTracker.NavStart.Blocked -> {
                    readTracker.onNavigationStarted()
                    setBusy(false)
                    try {
                        view.stopLoading()
                    } catch (e: Exception) {
                        Log.w("Readly", "Browser failed to stop blocked load: ${e.javaClass.name}: ${e.message}")
                    }
                    showError(blockedMessage(url))
                    updateReadButton()
                    return
                }
                is SiteBrowserLoadTracker.NavStart.Allowed -> {
                    readTracker.onNavigationStarted()
                    setBusy(false)
                    showStatus(getString(R.string.nyt_browser_loading_format, hostOf(url)))
                    updateReadButton()
                }
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            if (url.isNullOrBlank()) return
            val current = loadTracker.currentUrl() ?: return
            if (sameReadUrl(current, url)) {
                loadTracker.onSameDocument(url)
                updateReadButton()
                return
            }
            // Same-document navigation to a different page retires reads.
            readTracker.onSameDocumentChange()
            loadTracker.onSameDocument(url)
            setBusy(false)
            if (!SiteBrowserPolicy.allowsBrowsing(url)) {
                showError(blockedMessage(url))
            }
            updateReadButton()
        }

        override fun onPageFinished(view: WebView, url: String) {
            when (loadTracker.onFinished(url)) {
                is SiteBrowserLoadTracker.Finish.IgnoreStale -> return
                is SiteBrowserLoadTracker.Finish.RetainError -> {
                    updateReadButton()
                    return
                }
                is SiteBrowserLoadTracker.Finish.ShowLoaded -> {
                    showStatus(getString(R.string.nyt_browser_loaded_format, hostOf(url)))
                    updateReadButton()
                }
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (!request.isForMainFrame) return
            val target = request.url.toString()
            val detail = "Couldn't load ${hostOf(target)}: ${error.errorCode}: ${error.description}"
            if (loadTracker.onError(target, detail)) {
                showError(detail)
                updateReadButton()
            }
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            if (!request.isForMainFrame) return
            val target = request.url.toString()
            val detail = "Couldn't load ${hostOf(target)}: HTTP ${errorResponse.statusCode} ${errorResponse.reasonPhrase}"
            if (loadTracker.onError(target, detail)) {
                showError(detail)
                updateReadButton()
            }
        }
    }
}
