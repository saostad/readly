package app.readly.article

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.readly.ReadlyApp
import app.readly.input.sites.BrowserRead
import app.readly.input.sites.SiteBrowserLoadTracker
import app.readly.input.sites.SiteBrowserPolicy
import app.readly.input.sites.SiteBrowserReadTracker
import app.readly.input.sites.sameReadUrl
import app.readly.playback.ReaderService
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError

/**
 * The phone's article engine without a screen, for Android Auto: one owned
 * [GeckoSession] that is never attached to a view loads the link with the real
 * Bypass Paywalls Clean add-on, the capture helper snapshots the rendered page,
 * and [RenderedArticle] turns it into text — the same path as
 * [app.readly.ArticleActivity], so a link that reads on the phone reads in the car.
 *
 * The capture handshake keeps the activity's protections: the helper port must
 * come from the capture add-on's top-frame content script in this session on the
 * current document, and every snapshot must echo this read's fresh token.
 *
 * Nothing here can ask the driver to sign in or pass a human check, so those pages,
 * load errors and timeouts finish the read with an explanation. One automatic
 * reload is tried after a capture timeout, like on the phone. Main thread only.
 */
class HeadlessArticleReader(private val context: Context) {

    companion object {
        private const val TAG = "Readly"
    }

    private val main = Handler(Looper.getMainLooper())
    private val parseExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { Thread(it, "readly-car-parse") }

    private var engine: ArticleEngine? = null
    private var session: GeckoSession? = null

    private val readTracker = SiteBrowserReadTracker(SiteBrowserPolicy::allowsExtraction)
    private val loadTracker = SiteBrowserLoadTracker(
        SiteBrowserPolicy::allowsBrowsing,
        SiteBrowserPolicy::allowsExtraction,
    )
    private val captureAuth = ArticleCaptureAuth()
    private val parseGate = ArticleParseGate()

    /** The read in progress; null when idle. */
    private var job: Job? = null
    private var currentUrl: String? = null
    private var activeRead: BrowserRead? = null
    private var capture: ArticleCaptureSession? = null
    private var livePort: WebExtension.Port? = null
    private var generation = 0
    private var updateWarning: String? = null

    private class Job(val url: String, val onDone: (RenderedArticle.Outcome) -> Unit) {
        var navigated = false
        var autoRetried = false
        var startupRetried = false
        var done = false
    }

    private val captureTimeout = Runnable {
        val j = job ?: return@Runnable
        val cap = capture
        if (cap != null && (cap.isComplete() || cap.failure != null)) return@Runnable
        if (parseGate.isBusy()) return@Runnable
        cap?.cancel()
        Log.w(TAG, "Car article capture timed out gen=$generation host=${hostOf(j.url)}")
        autoRecaptureOrFail(j, "the page didn't finish loading in time")
    }

    private val engineListener = object : ArticleEngine.Listener {
        override fun onReadiness(canNavigate: Boolean, waitReason: String?, bpcVersion: String?, error: String?) {
            val j = job ?: return
            if (j.done) return
            if (error != null) {
                val eng = engine
                val s = session
                // One explicit retry per car read, as the phone's Reload would.
                if (!j.startupRetried && eng != null && s != null && eng.retryStartup()) {
                    j.startupRetried = true
                    Log.w(TAG, "Car article engine setup failed ($error); retrying once")
                    eng.ensureArticleSession(s, captureMessageDelegate)
                    return
                }
                finish(j, RenderedArticle.Outcome.Failed("The article engine couldn't start: $error"))
                return
            }
            if (canNavigate && !j.navigated) navigate(j)
        }

        override fun onUpdateWarning(warning: String) {
            updateWarning = warning
            Log.w(TAG, warning)
        }
    }

    /**
     * Reads [url] and calls [onDone] once on main with the article or why it failed.
     * Starting a new read abandons the previous one without calling its callback.
     */
    fun read(url: String, onDone: (RenderedArticle.Outcome) -> Unit) {
        cancel()
        val j = Job(url, onDone)
        if (!SiteBrowserPolicy.allowsExtraction(url)) {
            finish(j, RenderedArticle.Outcome.Failed("Can't open this page: ${SiteBrowserPolicy.blockReason(url)}"))
            return
        }
        job = j
        try {
            val eng = engine ?: ArticleEngine.get(context).also {
                engine = it
                it.addListener(engineListener)
            }
            val s = session ?: GeckoSession().also { s ->
                s.contentDelegate = contentDelegate
                s.progressDelegate = progressDelegate
                s.navigationDelegate = navigationDelegate
                s.permissionDelegate = permissionDelegate
                s.open(eng.runtime())
                // Never attached to a view: keep the page running as a foreground tab.
                s.setActive(true)
                session = s
            }
            eng.ensureArticleSession(s, captureMessageDelegate)
        } catch (e: Exception) {
            Log.e(TAG, "Car article engine failed to open a session", e)
            finish(j, RenderedArticle.Outcome.Failed("The article engine couldn't start: ${e.javaClass.simpleName}"))
        }
    }

    /** Abandons the read in progress (no callback) and stops the page. */
    fun cancel() {
        val j = job ?: return
        j.done = true
        job = null
        retireCapture()
        main.removeCallbacks(captureTimeout)
        try {
            session?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Car article stop failed: ${e.javaClass.name}: ${e.message}")
        }
    }

    /** Releases the session; the reader can't be used afterwards. */
    fun close() {
        cancel()
        engine?.removeListener(engineListener)
        engine = null
        parseExecutor.shutdownNow()
        try {
            session?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Car article session close failed: ${e.javaClass.name}: ${e.message}")
        }
        session = null
    }

    private fun finish(j: Job, outcome: RenderedArticle.Outcome) {
        if (j.done) return
        j.done = true
        if (job === j) job = null
        retireCapture()
        main.removeCallbacks(captureTimeout)
        if (outcome is RenderedArticle.Outcome.Failed) {
            Log.w(TAG, "Car article read failed for ${hostOf(j.url)}: ${outcome.message}")
            try {
                session?.stop()
            } catch (_: Exception) {
            }
        }
        j.onDone(outcome)
    }

    private fun retireCapture() {
        generation += 1
        readTracker.onNavigationStarted()
        capture?.cancel()
        capture = null
        activeRead = null
        disconnectPort()
        captureAuth.retire()
        parseGate.retire()
    }

    private fun navigate(j: Job) {
        val s = session ?: return
        j.navigated = true
        retireCapture()
        currentUrl = null
        when (loadTracker.onNavigationStarted(j.url)) {
            is SiteBrowserLoadTracker.NavStart.Blocked -> {
                finish(j, RenderedArticle.Outcome.Failed("Can't open this page: ${SiteBrowserPolicy.blockReason(j.url)}"))
                return
            }
            is SiteBrowserLoadTracker.NavStart.Allowed -> armCapture(j.url)
        }
        armTimeout()
        engine?.setTabActive(s)
        try {
            s.loadUri(j.url)
        } catch (e: Exception) {
            finish(j, RenderedArticle.Outcome.Failed("Couldn't load ${hostOf(j.url)}: ${e.javaClass.simpleName}"))
        }
    }

    private fun armTimeout() {
        main.removeCallbacks(captureTimeout)
        main.postDelayed(captureTimeout, ArticleConstants.CAPTURE_TIMEOUT_MS)
    }

    private fun armCapture(url: String) {
        val read = readTracker.startRead(url)
        activeRead = read
        capture?.cancel()
        capture = null
        if (read == null) {
            captureAuth.retire()
            disconnectPort()
            return
        }
        val token = captureAuth.arm(read)
        livePort?.let { postCaptureRequest(it, token) }
    }

    private fun autoRecaptureOrFail(j: Job, why: String) {
        if (j.autoRetried) {
            finish(j, RenderedArticle.Outcome.Failed("Couldn't read this page: $why."))
            return
        }
        j.autoRetried = true
        Log.w(TAG, "fallback=true attempted=car-capture reason=\"$why\" taken=reload-once host=${hostOf(j.url)}")
        try {
            session?.reload()
            armTimeout()
        } catch (e: Exception) {
            finish(j, RenderedArticle.Outcome.Failed("Couldn't read this page: ${e.javaClass.simpleName}."))
        }
    }

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

    private fun postCaptureRequest(port: WebExtension.Port, token: String) {
        val request = JSONObject()
            .put("protocol", ArticleCaptureProtocol.PROTOCOL)
            .put("kind", ArticleCaptureProtocol.KIND_REQUEST)
            .put("requestToken", token)
        try {
            port.postMessage(request)
        } catch (e: Exception) {
            Log.w(TAG, "Capture request failed: ${e.javaClass.name}: ${e.message}")
            if (port === livePort) disconnectPort()
        }
    }

    // ----------------------------------------------------- capture messaging ---

    private val captureMessageDelegate = object : WebExtension.MessageDelegate {
        override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
            // Port-only protocol: one-off messages carry no token and are dropped.
            return null
        }

        override fun onConnect(port: WebExtension.Port) {
            main.post { handleConnect(port) }
        }
    }

    private val capturePortDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            if (message !is JSONObject) return
            main.post { handlePayload(message, port) }
        }

        override fun onDisconnect(port: WebExtension.Port) {
            main.post { if (port === livePort) livePort = null }
        }
    }

    private fun handleConnect(port: WebExtension.Port) {
        val sender = port.sender
        fun reject(reason: String) {
            Log.w(TAG, "Car capture port rejected: $reason")
            try {
                port.disconnect()
            } catch (_: Exception) {
            }
        }
        if (port.name != ArticleConstants.NATIVE_APP) return reject("foreign port name")
        if (sender.webExtension?.id != ArticleConstants.CAPTURE_ID) return reject("foreign sender extension")
        if (sender.environmentType != WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT) {
            return reject("non-content-script environment")
        }
        if (!sender.isTopLevel()) return reject("non-top frame")
        if (sender.session !== session) return reject("foreign session")
        val read = activeRead
        if (job == null || read == null || !readTracker.isCurrent(read)) return reject("no armed read")
        if (ArticleCaptureProtocol.matchUrl(currentUrl, sender.url) != ArticleCaptureProtocol.UrlMatch.EXACT) {
            return reject("sender URL is not the current document")
        }
        if (!sameReadUrl(read.url, sender.url)) return reject("sender URL is not the armed read")
        val token = captureAuth.currentToken(read) ?: return reject("read has no live token")
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
            if (port === livePort) disconnectPort()
            return
        }
        postCaptureRequest(port, token)
    }

    private fun handlePayload(payload: JSONObject, port: WebExtension.Port) {
        if (port !== livePort) return
        val j = job ?: return
        val read = activeRead ?: return
        if (!readTracker.isCurrent(read) || parseGate.isBusy()) return
        val parsed = try {
            @Suppress("UNCHECKED_CAST")
            val map = jsonToMap(payload) as? Map<String, Any?> ?: return
            ArticleCaptureProtocol.parse(map)
        } catch (_: Exception) {
            return
        }
        if (parsed is ArticleCaptureProtocol.Parsed.Err) return
        val msg = (parsed as ArticleCaptureProtocol.Parsed.Ok).message
        if (!captureAuth.accepts(read, msg.requestToken)) return
        if (ArticleCaptureProtocol.matchUrl(currentUrl, msg.url) != ArticleCaptureProtocol.UrlMatch.EXACT) return
        if (!sameReadUrl(read.url, msg.url)) return
        var cap = capture
        if (msg is ArticleCaptureProtocol.Meta) {
            if (cap == null || cap.cancelled || cap.failure != null || cap.isComplete()) {
                cap = ArticleCaptureSession(generation, msg.captureId, msg.url)
                capture = cap
            } else if (cap.captureId != msg.captureId) {
                return
            }
        }
        val active = capture ?: return
        val failure = active.apply(msg)
        if (failure != null) {
            Log.w(TAG, "Car capture failed gen=$generation: $failure")
            autoRecaptureOrFail(j, "the article capture failed ($failure)")
            return
        }
        if (active.isComplete()) {
            main.removeCallbacks(captureTimeout)
            beginParse(j, read, active)
        }
    }

    private fun beginParse(j: Job, read: BrowserRead, cap: ArticleCaptureSession) {
        loadTracker.currentError()?.let {
            finish(j, RenderedArticle.Outcome.Failed(loadErrorText(it)))
            return
        }
        if (!parseGate.tryBegin(read)) return
        val html = cap.html() ?: ""
        val snapshotUrl = cap.pageUrl
        val callbackUrl = currentUrl
        val gateFlags = (cap.meta?.gateNodes.orEmpty() + cap.meta?.gateText.orEmpty()).size
        val truncated = cap.meta?.htmlTruncated == true
        val warning = updateWarning
        val app = context.applicationContext as? ReadlyApp
        val readHiddenText = ReaderService.isHiddenTextOn(context)
        try {
            parseExecutor.submit {
                val belongs = readTracker.snapshotBelongs(read, snapshotUrl, callbackUrl)
                val outcome = if (belongs) {
                    RenderedArticle.parse(app, html, snapshotUrl, gateFlags, truncated, warning, readHiddenText)
                } else {
                    null
                }
                main.post {
                    if (!parseGate.finish(read)) return@post
                    if (job !== j || outcome == null || !readTracker.isCurrent(read)) return@post
                    finish(j, outcome)
                }
            }
        } catch (e: RejectedExecutionException) {
            parseGate.finish(read)
            finish(j, RenderedArticle.Outcome.Failed("Read page failed: the reader is shutting down."))
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

    // ------------------------------------------------------------- delegates ---

    private val progressDelegate = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            if (session !== this@HeadlessArticleReader.session) return
            val j = job ?: return
            retireCapture()
            when (loadTracker.onNavigationStarted(url)) {
                is SiteBrowserLoadTracker.NavStart.Blocked ->
                    finish(j, RenderedArticle.Outcome.Failed(blockedMessage(url)))
                is SiteBrowserLoadTracker.NavStart.Allowed -> {
                    currentUrl = url
                    armCapture(url)
                    armTimeout()
                }
            }
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            if (session !== this@HeadlessArticleReader.session) return
            val j = job ?: return
            val url = currentUrl ?: return
            if (loadTracker.onFinished(url, success) is SiteBrowserLoadTracker.Finish.RetainError) {
                loadTracker.currentError()?.let { finish(j, RenderedArticle.Outcome.Failed(loadErrorText(it))) }
            }
        }
    }

    private val navigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest,
        ): GeckoResult<AllowOrDeny>? {
            if (session !== this@HeadlessArticleReader.session) return null
            if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                Log.w(TAG, "Car reader blocked a pop-up")
                return GeckoResult.deny()
            }
            if (!SiteBrowserPolicy.allowsBrowsing(request.uri)) {
                Log.w(TAG, "Car reader blocked ${blockedMessage(request.uri)}")
                return GeckoResult.deny()
            }
            return null
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? = null

        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            if (session !== this@HeadlessArticleReader.session || url == null) return
            if (job == null) return
            val previous = currentUrl
            if (previous != null && !sameReadUrl(previous, url)) {
                // Redirect, query change or SPA navigation: re-arm for the new document.
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
                armTimeout()
            } else if (previous == null) {
                currentUrl = url
            }
        }

        override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
            if (session !== this@HeadlessArticleReader.session) return null
            val j = job ?: return null
            val target = uri ?: currentUrl ?: j.url
            val detail = "Couldn't load ${hostOf(target)} (error ${error.code})."
            if (loadTracker.onError(target, detail)) finish(j, RenderedArticle.Outcome.Failed(detail))
            return null
        }
    }

    private val permissionDelegate = object : GeckoSession.PermissionDelegate {
        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: GeckoSession.PermissionDelegate.ContentPermission,
        ): GeckoResult<Int> {
            Log.w(TAG, "Car reader denied content permission ${perm.permission}")
            return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
        }

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback,
        ) = callback.reject()

        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<String>?,
            callback: GeckoSession.PermissionDelegate.Callback,
        ) = callback.reject()
    }

    private val contentDelegate = object : GeckoSession.ContentDelegate {
        override fun onExternalResponse(session: GeckoSession, response: org.mozilla.geckoview.WebResponse) {
            val j = job ?: return
            finish(j, RenderedArticle.Outcome.Failed("That link is a download, not an article."))
        }
    }

    private fun loadErrorText(err: String): String = when {
        err.startsWith("blocked:") -> blockedMessage(err.removePrefix("blocked:"))
        err.startsWith(SiteBrowserLoadTracker.LOAD_FAILED_PREFIX) ->
            "Couldn't load ${hostOf(err.removePrefix(SiteBrowserLoadTracker.LOAD_FAILED_PREFIX))}: the page failed to load."
        else -> err
    }

    private fun blockedMessage(url: String): String = "Blocked: ${SiteBrowserPolicy.blockReason(url)}"

    private fun hostOf(url: String?): String {
        if (url.isNullOrBlank()) return "the page"
        return try {
            URI(url).host ?: url
        } catch (_: Exception) {
            url
        }
    }
}
