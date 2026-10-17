package app.readly

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import app.readly.article.ArticleCaptureAuth
import app.readly.article.ArticleCaptureProtocol
import app.readly.article.ArticleCaptureSession
import app.readly.article.ArticleConstants
import app.readly.article.ArticleEngine
import app.readly.article.ArticleParseGate
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
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError

/**
 * Automatic article engine: every shared or pasted link opens here. The real
 * Bypass Paywalls Clean add-on processes the page inside GeckoView, the own
 * capture helper snapshots the rendered DOM automatically, and the sanitized
 * article is handed to the reader — no Open page / Read page taps.
 *
 * The browser stays visible for genuine sign-in/human-verification only. Any
 * main-frame navigation, reload, redirect, query change or SPA URL change
 * retires the armed capture. The helper talks over one owned native-messaging
 * port per document: native binds the port's sender (extension, session, top
 * frame, URL) at connect time, issues a fresh request token per read, and
 * every snapshot message must echo that token on the live port. URL matching
 * stays strict (fragment-insensitive, query-sensitive); the token
 * additionally rejects late messages from a prior document that carry the
 * same URL. A query/SPA transition reuses the live port with a fresh token
 * so the helper snapshots again after settling instead of idling. Every
 * parse completion is scoped to its originating read: stale completions
 * change nothing. Pause/stop/destroy/new-link discard stale parser results
 * and never start speech or launch activities from the background; resume
 * may trigger one automatic recapture, never autoplay. Recognizable walls
 * fail with an explanation and keep the page interactive; nothing gate-like
 * is ever spoken. No content diagnostics are persisted.
 */
class ArticleActivity : Activity() {

    companion object {
        private const val TAG = "Readly"
        const val EXTRA_URL = "articleUrl"

        /** Opens the automatic article engine for one exact link. */
        fun intent(context: Context, url: String): Intent =
            Intent(context, ArticleActivity::class.java).putExtra(EXTRA_URL, url)
    }

    private lateinit var geckoView: GeckoView
    private lateinit var statusView: TextView
    private lateinit var reloadButton: Button
    private lateinit var cancelButton: Button
    private lateinit var parseExecutor: ExecutorService

    private val main = Handler(Looper.getMainLooper())
    private var engine: ArticleEngine? = null
    private var session: GeckoSession? = null

    private val readTracker = SiteBrowserReadTracker(SiteBrowserPolicy::allowsExtraction)
    private val loadTracker = SiteBrowserLoadTracker(
        SiteBrowserPolicy::allowsBrowsing,
        SiteBrowserPolicy::allowsExtraction,
    )

    private var startUrl: String = ""
    private var currentUrl: String? = null
    private var pageTitle: String? = null
    private var navigated = false
    private var generation = 0
    private var activeRead: BrowserRead? = null
    private var capture: ArticleCaptureSession? = null
    private var livePort: WebExtension.Port? = null
    private val captureAuth = ArticleCaptureAuth()
    private val parseGate = ArticleParseGate()
    private var engineCanNavigate = false
    private var engineError: String? = null
    private var updateWarning: String? = null
    private var handoffDone = false
    private var autoRetried = false
    private var captureArmedAtMs = 0L

    private val pageTimeout = Runnable {
        if (navigated && !handoffDone && loadTracker.isLoading()) {
            showStatus(getString(R.string.article_loading_slow_format, hostOf(currentUrl ?: startUrl)))
        }
    }
    private val captureTimeout = Runnable {
        val cap = capture
        val read = activeRead
        if (handoffDone || read == null || !readTracker.isCurrent(read)) return@Runnable
        if (cap != null && (cap.isComplete() || cap.failure != null || cap.cancelled)) return@Runnable
        cap?.cancel()
        Log.w(TAG, "Capture timed out gen=$generation")
        maybeAutoRecapture("the article capture timed out")
    }

    private val engineListener = object : ArticleEngine.Listener {
        override fun onReadiness(
            canNavigate: Boolean,
            waitReason: String?,
            bpcVersion: String?,
            error: String?,
        ) {
            engineCanNavigate = canNavigate
            engineError = error
            if (error != null) {
                showError("Article engine failed: $error")
                return
            }
            if (canNavigate) {
                if (!navigated) {
                    navigateTo(startUrl)
                } else if (!handoffDone && !parseGate.isBusy()) {
                    restoreStatusFromLoad()
                }
            } else {
                showStatus(waitReason ?: getString(R.string.article_starting))
            }
        }

        override fun onUpdateWarning(warning: String) {
            updateWarning = warning
            Log.w(TAG, warning)
            if (!handoffDone) {
                showStatus(warning)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_article)
        geckoView = findViewById(R.id.articleGeckoView)
        statusView = findViewById(R.id.articleStatus)
        reloadButton = findViewById(R.id.articleReloadButton)
        cancelButton = findViewById(R.id.articleCancelButton)
        parseExecutor = Executors.newSingleThreadExecutor { Thread(it, "readly-article-parse") }

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (!SiteBrowserPolicy.allowsExtraction(url)) {
            showError("Can't open this page: ${SiteBrowserPolicy.blockReason(url)}: $url")
            geckoView.visibility = View.GONE
            reloadButton.isEnabled = false
            return
        }
        startUrl = url
        // Warm the voice while the page loads; no Now Playing until handoff.
        ReaderService.preload(this)
        reloadButton.setOnClickListener {
            val eng = engine
            val s = session
            // A failed engine setup is retryable from here: clear the failure,
            // re-prepare the add-ons (never reinstalling over a working
            // install), and navigate once readiness is observed. Bounded and
            // explicit — one attempt per tap, never automatic.
            if (eng != null && engineError != null && eng.retryStartup()) {
                autoRetried = false
                showStatus(getString(R.string.article_starting))
                if (s != null) {
                    eng.ensureArticleSession(s, captureMessageDelegate)
                }
                return@setOnClickListener
            }
            if (s == null) return@setOnClickListener
            autoRetried = false
            try {
                s.reload()
            } catch (e: Exception) {
                showError("Reload failed: ${e.javaClass.name}: ${e.message}")
            }
        }
        cancelButton.setOnClickListener { finish() }

        val eng = ArticleEngine.get(this)
        engine = eng
        eng.addListener(engineListener)
        val s = GeckoSession()
        s.contentDelegate = contentDelegate
        s.progressDelegate = progressDelegate
        s.navigationDelegate = navigationDelegate
        s.permissionDelegate = permissionDelegate
        try {
            s.open(eng.runtime())
        } catch (e: Exception) {
            showError("Article engine failed: ${e.javaClass.name}: ${e.message}")
            return
        }
        session = s
        geckoView.setSession(s)
        eng.ensureArticleSession(s, captureMessageDelegate)
        showStatus(getString(R.string.article_starting))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isBlank()) return
        if (!SiteBrowserPolicy.allowsExtraction(url)) {
            showError("Can't open this page: ${SiteBrowserPolicy.blockReason(url)}: $url")
            return
        }
        autoRetried = false
        handoffDone = false
        navigateTo(url)
    }

    override fun onPause() {
        readTracker.onPaused()
        capture?.cancel()
        capture = null
        disconnectPort()
        captureAuth.retire()
        parseGate.retire()
        main.removeCallbacks(pageTimeout)
        main.removeCallbacks(captureTimeout)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        readTracker.onResumed()
        if (handoffDone) return
        if (engineError != null) return
        val read = activeRead
        if (navigated && (read == null || !readTracker.isCurrent(read))) {
            // The armed capture retired while backgrounded. Recapture once
            // automatically when the page is still there; never autoplay.
            maybeAutoRecapture(null)
        } else {
            restoreStatusFromLoad()
        }
    }

    override fun onStop() {
        readTracker.onStopped()
        super.onStop()
    }

    override fun onDestroy() {
        readTracker.onDestroyed()
        main.removeCallbacks(pageTimeout)
        main.removeCallbacks(captureTimeout)
        capture?.cancel()
        capture = null
        disconnectPort()
        captureAuth.retire()
        parseGate.retire()
        engine?.removeListener(engineListener)
        if (::parseExecutor.isInitialized) parseExecutor.shutdownNow()
        try {
            geckoView.releaseSession()
        } catch (_: Exception) {
        }
        try {
            session?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Session close failed: ${e.javaClass.name}: ${e.message}")
        }
        session = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ navigation ---

    private fun navigateTo(url: String) {
        val s = session ?: return
        if (engineError != null) return
        generation += 1
        readTracker.onNavigationStarted()
        capture?.cancel()
        capture = null
        activeRead = null
        disconnectPort()
        captureAuth.retire()
        parseGate.retire()
        navigated = true
        startUrl = url
        currentUrl = null
        pageTitle = null
        when (loadTracker.onNavigationStarted(url)) {
            is SiteBrowserLoadTracker.NavStart.Blocked -> {
                showError(blockedMessage(url))
                return
            }
            is SiteBrowserLoadTracker.NavStart.Allowed -> {
                armCapture(url)
                showStatus(getString(R.string.article_loading_format, hostOf(url)))
            }
        }
        main.removeCallbacks(pageTimeout)
        main.removeCallbacks(captureTimeout)
        main.postDelayed(pageTimeout, ArticleConstants.PAGE_STOP_TIMEOUT_MS)
        main.postDelayed(captureTimeout, ArticleConstants.CAPTURE_TIMEOUT_MS)
        captureArmedAtMs = android.os.SystemClock.elapsedRealtime()
        engine?.setTabActive(s)
        try {
            s.loadUri(url)
        } catch (e: Exception) {
            showError("Couldn't load ${hostOf(url)}: ${e.javaClass.name}: ${e.message}")
        }
    }

    /**
     * Arms one automatic capture for [url] (idempotent per navigation): the
     * read gets a fresh request token, retiring any prior document's token.
     * Full navigations disconnect the old port, so the new document's helper
     * connects and is issued this token on connect. A query/SPA transition
     * keeps the live port (same document) and is issued a fresh request on
     * it immediately, so the helper snapshots again after settling instead
     * of waiting for the watchdog.
     */
    private fun armCapture(url: String) {
        val read = readTracker.startRead(url)
        activeRead = read
        capture?.cancel()
        capture = null
        if (read == null) {
            captureAuth.retire()
            disconnectPort()
            Log.w(TAG, "Capture not armed (background or ineligible page)")
            return
        }
        val token = captureAuth.arm(read)
        livePort?.let { postCaptureRequest(it, token) }
    }

    /** Drops the live helper port (full navigation, pause, destroy). */
    private fun disconnectPort() {
        val port = livePort
        livePort = null
        if (port == null) return
        try {
            port.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Port disconnect failed: ${e.javaClass.name}: ${e.message}")
        }
    }

    /** Issues one capture request (token included) on the owned port. */
    private fun postCaptureRequest(port: WebExtension.Port, token: String) {
        val request = JSONObject()
            .put("protocol", ArticleCaptureProtocol.PROTOCOL)
            .put("kind", ArticleCaptureProtocol.KIND_REQUEST)
            .put("requestToken", token)
        try {
            port.postMessage(request)
        } catch (e: Exception) {
            Log.w(TAG, "Capture request failed: ${e.javaClass.name}: ${e.message}")
            if (port === livePort) {
                disconnectPort()
            }
        }
    }

    /**
     * One automatic recapture after a timeout or background retire: reloads
     * the settled page once so the helper snapshots again. Permanent
     * one-shot failure never strands the user, and the budget never loops.
     */
    private fun maybeAutoRecapture(failureNote: String?) {
        if (handoffDone || engineError != null) {
            if (failureNote != null && engineError == null) {
                showError("Couldn't read this page: $failureNote.")
            }
            return
        }
        val s = session ?: return
        val current = loadTracker.currentUrl() ?: currentUrl ?: startUrl
        if (autoRetried) {
            if (failureNote != null) {
                showError("Couldn't read this page: $failureNote. Reload to try again.")
            } else {
                restoreStatusFromLoad()
            }
            return
        }
        autoRetried = true
        Log.i(TAG, "Auto recapture (reload once) for ${hostOf(current)}")
        showStatus(getString(R.string.article_retrying))
        try {
            s.reload()
        } catch (e: Exception) {
            showError("Couldn't read this page: ${e.javaClass.name}: ${e.message}")
        }
    }

    // ----------------------------------------------------- capture messaging ---

    private val captureMessageDelegate = object : WebExtension.MessageDelegate {
        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender,
        ): GeckoResult<Any>? {
            // The helper is port-only: one-off messages carry no request
            // token and are always dropped, however valid they look.
            if (nativeApp == ArticleConstants.NATIVE_APP &&
                sender.webExtension?.id == ArticleConstants.CAPTURE_ID
            ) {
                Log.w(TAG, "Capture dropped: one-off message without a port")
            }
            return null
        }

        override fun onConnect(port: WebExtension.Port) {
            main.post { handleConnectMain(port) }
        }
    }

    private val capturePortDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            if (port !== livePort) return
            if (message !is JSONObject) return
            handleCapturePayload(message, port)
        }

        override fun onDisconnect(port: WebExtension.Port) {
            main.post {
                if (port === livePort) {
                    livePort = null
                }
            }
        }
    }

    /**
     * Binds one helper port to the armed read: the port's native sender
     * (extension, content-script environment, top frame, session, URL) must
     * match the current document, and the read must hold a live token. Ports
     * that fail any check are disconnected immediately; the matching port is
     * issued this read's token, which every snapshot message must echo back.
     */
    private fun handleConnectMain(port: WebExtension.Port) {
        val sender = port.sender
        fun reject(reason: String) {
            Log.w(TAG, "Capture port rejected: $reason")
            try {
                port.disconnect()
            } catch (_: Exception) {
            }
        }
        if (port.name != ArticleConstants.NATIVE_APP) {
            reject("foreign port name"); return
        }
        if (sender.webExtension?.id != ArticleConstants.CAPTURE_ID) {
            reject("foreign sender extension"); return
        }
        if (sender.environmentType != WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT) {
            reject("non-content-script environment"); return
        }
        if (!sender.isTopLevel()) {
            reject("non-top frame"); return
        }
        if (sender.session !== session) {
            reject("foreign session"); return
        }
        val read = activeRead
        if (read == null || !readTracker.isCurrent(read)) {
            reject("no armed read"); return
        }
        val current = currentUrl
        if (ArticleCaptureProtocol.matchUrl(current, sender.url) != ArticleCaptureProtocol.UrlMatch.EXACT) {
            reject("sender URL is not the current document"); return
        }
        if (!sameReadUrl(read.url, sender.url)) {
            reject("sender URL is not the armed read"); return
        }
        val token = captureAuth.currentToken(read)
        if (token == null) {
            reject("read has no live token"); return
        }
        val previous = livePort
        if (previous != null && previous !== port) {
            try {
                previous.disconnect()
            } catch (_: Exception) {
            }
        }
        livePort = port
        try {
            port.setDelegate(capturePortDelegate)
        } catch (e: Exception) {
            Log.w(TAG, "Port delegate failed: ${e.javaClass.name}: ${e.message}")
            if (port === livePort) {
                disconnectPort()
            }
            return
        }
        postCaptureRequest(port, token)
    }

    private fun handleCapturePayload(payload: JSONObject, port: WebExtension.Port) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            handleCapturePayloadMain(payload, port)
        } else {
            main.post { handleCapturePayloadMain(payload, port) }
        }
    }

    private fun handleCapturePayloadMain(payload: JSONObject, port: WebExtension.Port) {
        if (port !== livePort) return
        val read = activeRead ?: return
        if (!readTracker.isCurrent(read)) return
        if (handoffDone || parseGate.isBusy()) return
        val parsed = try {
            @Suppress("UNCHECKED_CAST")
            val map = jsonToMap(payload) as? Map<String, Any?>
            if (map == null) return
            ArticleCaptureProtocol.parse(map)
        } catch (_: Exception) {
            return
        }
        if (parsed is ArticleCaptureProtocol.Parsed.Err) return
        val msg = (parsed as ArticleCaptureProtocol.Parsed.Ok).message
        // The echoed token binds the message to this read's document. A late
        // message from a prior document fails here even when its URL still
        // matches (same-URL reload): the token rotates on every navigation.
        if (!captureAuth.accepts(read, msg.requestToken)) return
        // Strict current-document match on the page-supplied URL. Query
        // differences mean a different document. (The native sender URL was
        // bound when this port connected; the port outlives SPA transitions
        // whose sender URL predates them, so per-message sender checks would
        // wrongly reject the fresh token's snapshots.)
        val current = currentUrl
        if (ArticleCaptureProtocol.matchUrl(current, msg.url) != ArticleCaptureProtocol.UrlMatch.EXACT) return
        if (!sameReadUrl(read.url, msg.url)) return
        val gen = generation
        var cap = capture
        if (msg is ArticleCaptureProtocol.Meta) {
            if (cap == null || cap.cancelled || cap.failure != null || cap.isComplete()) {
                cap = ArticleCaptureSession(gen, msg.captureId, msg.url)
                capture = cap
                if (loadTracker.currentError() == null) {
                    showStatus(getString(R.string.article_reading))
                }
            } else if (cap.captureId != msg.captureId) {
                return
            }
        }
        val active = capture ?: return
        val failure = active.apply(msg)
        if (failure != null) {
            Log.w(TAG, "Capture failed gen=$gen: $failure")
            maybeAutoRecapture("the article capture failed ($failure)")
            return
        }
        if (active.isComplete()) {
            main.removeCallbacks(captureTimeout)
            beginParse(read, active)
        }
    }

    private fun jsonToMap(value: Any?): Any? = when (value) {
        is JSONObject -> {
            val map = LinkedHashMap<String, Any?>()
            val keys = value.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = jsonToMap(value.get(k))
            }
            map
        }
        is JSONArray -> List(value.length()) { jsonToMap(value.get(it)) }
        JSONObject.NULL -> null
        else -> value
    }

    // ------------------------------------------------------------- parsing ---

    private fun beginParse(read: BrowserRead, cap: ArticleCaptureSession) {
        if (handoffDone) return
        if (!readTracker.isCurrent(read)) return
        // Never parse a snapshot for a failed load; the load error owns the UI.
        loadTracker.currentError()?.let { showError(loadErrorText(it)); return }
        if (!parseGate.tryBegin(read)) return
        showStatus(getString(R.string.article_reading))
        val html = cap.html() ?: ""
        val snapshotUrl = cap.pageUrl
        val callbackUrl = currentUrl
        val gateFlags = (cap.meta?.gateNodes.orEmpty() + cap.meta?.gateText.orEmpty()).size
        val truncated = cap.meta?.htmlTruncated == true
        try {
            parseExecutor.submit {
                parseSnapshot(read, html, snapshotUrl, callbackUrl, gateFlags, truncated)
            }
        } catch (e: RejectedExecutionException) {
            parseGate.finish(read)
            showError("Read page failed: ${e.javaClass.name}: ${e.message}")
        }
    }

    private fun parseSnapshot(
        read: BrowserRead,
        html: String,
        snapshotUrl: String?,
        callbackUrl: String?,
        gateFlags: Int,
        truncated: Boolean,
    ) {
        try {
            if (!readTracker.snapshotBelongs(read, snapshotUrl, callbackUrl)) {
                postParseStale(read)
                return
            }
            val app = application as? ReadlyApp
            val snapshot = app?.siteRules?.snapshotForReading()
            val usage = SiteUsage()
            val doc = try {
                ArticleFetcher(ArticleFetcher::httpUrlOpener)
                    .extractRenderedHtml(html, snapshotUrl ?: "", snapshot, usage)
            } catch (e: Exception) {
                // Recognizable walls land here with an explanation; the page
                // stays interactive for genuine sign-in, never spoken.
                postParseError(read, e.message ?: "${e.javaClass.name}: (no details)")
                return
            }
            val clean = TextSanitizer.sanitize(doc.text, SourceFormat.HTML)
            if (clean.isBlank()) {
                postParseError(read, "Read page failed: no readable article text was found on this page.")
                return
            }
            SiteBrowserPolicy.handoffTooLargeError(clean.length)?.let { tooLarge ->
                postParseError(read, tooLarge)
                return
            }
            val startupError = if (snapshot == null) {
                app?.siteRules?.uiState()?.startupError
            } else {
                null
            }
            val gateNote = if (gateFlags > 0) {
                "The page shows possible subscription prompts."
            } else {
                null
            }
            val truncNote = if (truncated) {
                "The rendered page was truncated to 5 MB."
            } else {
                null
            }
            val notice = listOfNotNull(
                SiteBrowserPolicy.readingNotice(
                    doc.notice, usage.cleanupApplied, usage.noticeHost(), startupError,
                ),
                gateNote, truncNote, updateWarning,
            ).joinToString(" ").takeIf { it.isNotBlank() }
            postHandoff(read, clean, doc.title, notice)
        } catch (e: Exception) {
            postParseError(read, "Read page failed: ${e.javaClass.name}: ${e.message}")
        }
    }

    /**
     * Every completion below is scoped to its originating [read]: only the
     * read that owns the parse slot releases it, and only a still-current
     * read may touch status or start a handoff. A retired parse's completion
     * is a silent no-op — it can neither clear a newer parse's busy flag nor
     * overwrite its error/status.
     */
    private fun postParseStale(read: BrowserRead) {
        runOnUiThread {
            parseGate.finish(read)
        }
    }

    private fun postParseError(read: BrowserRead, message: String) {
        runOnUiThread {
            if (!parseGate.finish(read)) return@runOnUiThread
            if (!readTracker.isCurrent(read)) return@runOnUiThread
            if (handoffDone) return@runOnUiThread
            // Keep the page interactive for genuine sign-in; a new settled
            // navigation re-arms automatically.
            showError(message)
        }
    }

    private fun postHandoff(read: BrowserRead, clean: String, title: String, notice: String?) {
        runOnUiThread {
            if (!parseGate.isOwner(read)) return@runOnUiThread
            if (!readTracker.isCurrent(read)) {
                parseGate.finish(read)
                return@runOnUiThread
            }
            // Never hand off a snapshot for a failed load.
            loadTracker.currentError()?.let {
                parseGate.finish(read)
                showError(loadErrorText(it))
                return@runOnUiThread
            }
            if (handoffDone) {
                parseGate.finish(read)
                return@runOnUiThread
            }
            val finalUrl = currentUrl
            when (val outcome = readTracker.finishRead(read, finalUrl) {
                ReaderService.read(this, clean, title, SourceFormat.PLAIN, notice)
                startActivity(Intent(this, NowPlayingActivity::class.java))
                overridePendingTransition(R.anim.screen_slide_in, R.anim.screen_slide_out)
                handoffDone = true
                finish()
            }) {
                is SiteBrowserReadTracker.HandoffOutcome.Stale -> {
                    // Navigation or background retired the read; discard.
                    parseGate.finish(read)
                }
                is SiteBrowserReadTracker.HandoffOutcome.Failed -> {
                    parseGate.finish(read)
                    showError(outcome.reason)
                }
                is SiteBrowserReadTracker.HandoffOutcome.Done -> {
                    parseGate.finish(read)
                    loadTracker.onReadSucceeded()
                }
            }
        }
    }

    // ------------------------------------------------------------- delegates ---

    private val progressDelegate = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            if (session !== this@ArticleActivity.session) return
            // Every main-frame start retires the armed capture, including
            // same-URL reloads and redirects. The old document's port and
            // token die here; its late messages are rejected on both.
            generation += 1
            readTracker.onNavigationStarted()
            capture?.cancel()
            capture = null
            activeRead = null
            disconnectPort()
            captureAuth.retire()
            parseGate.retire()
            when (loadTracker.onNavigationStarted(url)) {
                is SiteBrowserLoadTracker.NavStart.Blocked -> {
                    showError(blockedMessage(url))
                    return
                }
                is SiteBrowserLoadTracker.NavStart.Allowed -> {
                    currentUrl = url
                    armCapture(url)
                    showStatus(getString(R.string.article_loading_format, hostOf(url)))
                    main.removeCallbacks(captureTimeout)
                    main.postDelayed(captureTimeout, ArticleConstants.CAPTURE_TIMEOUT_MS)
                }
            }
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            if (session !== this@ArticleActivity.session) return
            val url = currentUrl ?: return
            // A finish never erases its navigation's error, and an
            // unsuccessful stop is itself a failure — never "Loaded".
            when (loadTracker.onFinished(url, success)) {
                is SiteBrowserLoadTracker.Finish.IgnoreStale -> return
                is SiteBrowserLoadTracker.Finish.RetainError -> {
                    main.removeCallbacks(pageTimeout)
                    loadTracker.currentError()?.let { showError(loadErrorText(it)) }
                    return
                }
                is SiteBrowserLoadTracker.Finish.ShowLoaded -> {
                    main.removeCallbacks(pageTimeout)
                    if (!handoffDone && !parseGate.isBusy() && engineError == null) {
                        showStatus(getString(R.string.article_loaded_format, hostOf(url)))
                    }
                }
            }
        }
    }

    private val navigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest,
        ): GeckoResult<AllowOrDeny>? {
            if (session !== this@ArticleActivity.session) return null
            if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                showError("Blocked: this reader opens no pop-up windows.")
                return GeckoResult.deny()
            }
            val target = request.uri
            if (!SiteBrowserPolicy.allowsBrowsing(target)) {
                showError(blockedMessage(target))
                return GeckoResult.deny()
            }
            return null
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            showError("Blocked: this reader opens no pop-up windows.")
            return null
        }

        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            if (session !== this@ArticleActivity.session) return
            if (url == null) return
            val previous = currentUrl
            if (previous != null && !sameReadUrl(previous, url)) {
                // Redirect, query change or SPA navigation: retire the armed
                // capture and re-arm for the new document. The live port
                // survives (same document context); armCapture issues it a
                // fresh request token at once, so the helper snapshots the
                // new URL after settling. The old token's messages die.
                generation += 1
                readTracker.onSameDocumentChange()
                capture?.cancel()
                capture = null
                activeRead = null
                captureAuth.retire()
                parseGate.retire()
                loadTracker.onSameDocument(url)
                currentUrl = url
                armCapture(url)
                main.removeCallbacks(captureTimeout)
                main.postDelayed(captureTimeout, ArticleConstants.CAPTURE_TIMEOUT_MS)
                showStatus(getString(R.string.article_loading_format, hostOf(url)))
            } else if (previous == null) {
                currentUrl = url
            }
        }

        override fun onLoadError(
            session: GeckoSession,
            uri: String?,
            error: WebRequestError,
        ): GeckoResult<String>? {
            if (session !== this@ArticleActivity.session) return null
            val target = uri ?: currentUrl ?: startUrl
            val detail = "Couldn't load ${hostOf(target)}: ${error.code}"
            if (loadTracker.onError(target, detail)) {
                showError(detail)
            }
            return null
        }
    }

    private val permissionDelegate = object : GeckoSession.PermissionDelegate {
        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: GeckoSession.PermissionDelegate.ContentPermission,
        ): GeckoResult<Int> {
            showError("Blocked: this reader denies website permissions.")
            Log.w(TAG, "Denied content permission ${perm.permission} for ${perm.uri}")
            return GeckoResult.fromValue(
                GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY,
            )
        }

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback,
        ) {
            showError("Blocked: this reader denies camera and microphone access.")
            callback.reject()
        }

        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<String>?,
            callback: GeckoSession.PermissionDelegate.Callback,
        ) {
            showError("Blocked: this reader denies app permissions.")
            callback.reject()
        }
    }

    private val contentDelegate = object : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            if (session !== this@ArticleActivity.session) return
            pageTitle = title
        }

        override fun onExternalResponse(session: GeckoSession, response: org.mozilla.geckoview.WebResponse) {
            if (session !== this@ArticleActivity.session) return
            showError("Blocked: this reader opens no downloads.")
        }

        override fun onCloseRequest(session: GeckoSession) {
            if (session !== this@ArticleActivity.session) return
            finish()
        }
    }

    // ------------------------------------------------------------------ UI ---

    private fun restoreStatusFromLoad() {
        val state = loadTracker.snapshot()
        val err = state.error
        when {
            engineError != null -> showError("Article engine failed: $engineError")
            err != null -> showError(loadErrorText(err))
            state.loading -> showStatus(getString(R.string.article_loading_format, hostOf(state.currentUrl)))
            state.currentUrl != null -> showStatus(
                getString(R.string.article_loaded_format, hostOf(state.currentUrl)),
            )
        }
    }

    /** User-visible text for a [SiteBrowserLoadTracker] error marker. */
    private fun loadErrorText(err: String): String = when {
        err.startsWith("blocked:") -> blockedMessage(err.removePrefix("blocked:"))
        err.startsWith(SiteBrowserLoadTracker.LOAD_FAILED_PREFIX) -> {
            val url = err.removePrefix(SiteBrowserLoadTracker.LOAD_FAILED_PREFIX)
            "Couldn't load ${hostOf(url)}: the page failed to load."
        }
        else -> err
    }

    private fun blockedMessage(url: String): String =
        getString(R.string.article_blocked_format, SiteBrowserPolicy.blockReason(url))

    private fun showStatus(text: String) {
        statusView.setTextColor(getColor(R.color.text3))
        statusView.text = text
    }

    private fun showError(text: String) {
        Log.w(TAG, text)
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
}
