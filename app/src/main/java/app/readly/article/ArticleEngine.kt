package app.readly.article

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.readly.playback.ReaderService
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * App-owned article engine: one lazy [GeckoRuntime] per process, the actual
 * unmodified signed BPC add-on plus the own capture helper, and the daily
 * add-on update lifecycle. The article activity binds one owned session via
 * [ensureArticleSession] and navigates only after observed readiness.
 *
 * Readiness requires enabled AND ready for every install path — fresh
 * installs and pre-installed same-version copies alike. `installPending` is
 * set BEFORE calling install(), so an early `onEnabled` never navigates
 * alone. There is no path that navigates on enabled-alone.
 *
 * All BPC setup state lives in one [BpcStartup] machine shared by the
 * normal read path and the manual settings-check path, so both reconcile
 * observed metadata, enabling, and readiness identically. Setup callbacks
 * are attempt-scoped: a retry opens a fresh attempt and stale callbacks
 * from older attempts are ignored. Concurrent read/manual callers join one
 * shared prepare round bounded by its own watchdog. A failed setup is
 * retryable through [retryStartup] (one explicit attempt per call, never
 * automatic).
 *
 * Update checks run once daily while the engine is in use (automatic checks
 * never create the runtime); manual checks from settings may start it. A
 * null `controller.update()` result means "no update" (success, keep
 * current), never a failure. Failures keep the known-good installed add-on
 * with a visible warning; the bundled copy is only installed when missing,
 * never over a working install. Signatures are never disabled; only the
 * known BPC id is installed/updated.
 */
class ArticleEngine private constructor(appContext: Context) {

    companion object {
        private const val TAG = "Readly"

        // Holds only the application context (via get()), never an activity.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: ArticleEngine? = null

        /** Process-singleton; safe to call on any thread. */
        fun get(context: Context): ArticleEngine {
            return instance ?: synchronized(this) {
                instance ?: ArticleEngine(context.applicationContext).also { instance = it }
            }
        }

        private fun installErrorName(code: Int): String = when (code) {
            WebExtension.InstallException.ErrorCodes.ERROR_NETWORK_FAILURE -> "NETWORK_FAILURE"
            WebExtension.InstallException.ErrorCodes.ERROR_INCORRECT_HASH -> "INCORRECT_HASH"
            WebExtension.InstallException.ErrorCodes.ERROR_CORRUPT_FILE -> "CORRUPT_FILE"
            WebExtension.InstallException.ErrorCodes.ERROR_FILE_ACCESS -> "FILE_ACCESS"
            WebExtension.InstallException.ErrorCodes.ERROR_SIGNEDSTATE_REQUIRED -> "SIGNEDSTATE_REQUIRED"
            WebExtension.InstallException.ErrorCodes.ERROR_UNEXPECTED_ADDON_TYPE -> "UNEXPECTED_ADDON_TYPE"
            WebExtension.InstallException.ErrorCodes.ERROR_UNEXPECTED_ADDON_VERSION -> "UNEXPECTED_ADDON_VERSION"
            WebExtension.InstallException.ErrorCodes.ERROR_INCORRECT_ID -> "INCORRECT_ID"
            WebExtension.InstallException.ErrorCodes.ERROR_INVALID_DOMAIN -> "INVALID_DOMAIN"
            WebExtension.InstallException.ErrorCodes.ERROR_BLOCKLISTED -> "BLOCKLISTED"
            WebExtension.InstallException.ErrorCodes.ERROR_INCOMPATIBLE -> "INCOMPATIBLE"
            WebExtension.InstallException.ErrorCodes.ERROR_UNSUPPORTED_ADDON_TYPE -> "UNSUPPORTED_ADDON_TYPE"
            WebExtension.InstallException.ErrorCodes.ERROR_ADMIN_INSTALL_ONLY -> "ADMIN_INSTALL_ONLY"
            WebExtension.InstallException.ErrorCodes.ERROR_SOFT_BLOCKED -> "SOFT_BLOCKED"
            WebExtension.InstallException.ErrorCodes.ERROR_USER_CANCELED -> "USER_CANCELED"
            WebExtension.InstallException.ErrorCodes.ERROR_POSTPONED -> "POSTPONED"
            else -> "UNKNOWN($code)"
        }
    }

    /** Engine readiness and update events, always delivered on main. */
    interface Listener {
        fun onReadiness(
            canNavigate: Boolean,
            waitReason: String?,
            bpcVersion: String?,
            error: String?,
        )
        fun onUpdateWarning(warning: String)
    }

    private val app = appContext
    private val main = Handler(Looper.getMainLooper())
    private val updateExecutor = Executors.newSingleThreadExecutor { Thread(it, "readly-bpc-update") }

    private val listeners = mutableSetOf<Listener>()
    private var runtime: GeckoRuntime? = null
    private var delegatesSet = false

    private var captureExt: WebExtension? = null
    private var bpcExt: WebExtension? = null
    private var captureOk = false
    private val startup = BpcStartup()
    private var extTimeoutArmed = false
    private var prepareTimeoutTask: Runnable? = null

    private val coordinator = BpcUpdateCoordinator(
        store = PrefsBpcUpdateStore(
            app.getSharedPreferences(ReaderService.PREFS_NAME, Context.MODE_PRIVATE),
        ),
        isAutoEnabled = {
            app.getSharedPreferences(ReaderService.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(ReaderService.KEY_NYT_AUTO_UPDATE, true)
        },
        clock = { System.currentTimeMillis() },
        runBackground = updateExecutor::execute,
        updateRunner = { installed, onDone -> runEngineUpdate(installed, onDone) },
        timeoutMs = ArticleConstants.UPDATE_TIMEOUT_MS,
        scheduleTimeout = { delayMs, task -> main.postDelayed(task, delayMs) },
    )

    private val extTimeout = Runnable {
        val snap = startup.snapshot()
        if (snap.error == null && !ArticleReadiness.canNavigate(readinessState())) {
            fail(
                "Extension startup timed out after ${ArticleConstants.EXT_TIMEOUT_MS}ms " +
                    "(bpc=${bpcExt?.id} enabled=${snap.enabledObserved} " +
                    "ready=${snap.readyObserved} captureOk=$captureOk)",
            )
        }
        // Hung list/install/enable rounds are bounded by the separate prepare
        // watchdog, which releases manual waiters with the actual timeout.
    }

    fun addListener(listener: Listener) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listeners.add(listener)
            emitLocked()
        } else {
            main.post {
                listeners.add(listener)
                emitLocked()
            }
        }
    }

    fun removeListener(listener: Listener) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listeners.remove(listener)
        } else {
            main.post { listeners.remove(listener) }
        }
    }

    /**
     * The process runtime, creating it on first use. Must be called on main;
     * the article activity opens its session on this before binding.
     */
    fun runtime(): GeckoRuntime = ensureRuntime()

    /**
     * Binds one owned [session] for article reading: installs/enables the
     * known add-ons once and attaches the capture message delegate to this
     * session. The session must already be open on [runtime]. Safe to call
     * again for a new session after activity recreation. Main thread only.
     */
    fun ensureArticleSession(
        session: GeckoSession,
        messageDelegate: WebExtension.MessageDelegate,
    ) {
        val rt = ensureRuntime()
        val controller = rt.webExtensionController
        controller.setTabActive(session, true)
        val knownCapture = captureExt
        if (knownCapture != null && captureOk) {
            try {
                session.webExtensionController.setMessageDelegate(
                    knownCapture, messageDelegate, ArticleConstants.NATIVE_APP,
                )
            } catch (e: Exception) {
                fail("Capture helper attach failed: ${e.javaClass.name}: ${e.message}")
                return
            }
        } else {
            ensureCapture(controller, session, messageDelegate)
        }
        if (startup.needsPrepare()) {
            prepareBpc(controller, onSettled = null)
        } else {
            emitLocked()
        }
        if (!extTimeoutArmed) {
            extTimeoutArmed = true
            main.postDelayed(extTimeout, ArticleConstants.EXT_TIMEOUT_MS)
        }
    }

    /**
     * Explicit bounded retry after a failed setup (article Reload): clears
     * the failure, keeps the known signed add-on and any observed readiness,
     * opens a fresh setup attempt, and re-arms the startup watchdog.
     * Returns false when there is no failure to retry. Never automatic, and
     * never reinstalls over a working add-on. Main thread only.
     */
    fun retryStartup(): Boolean {
        if (startup.retry() == null) return false
        main.removeCallbacks(extTimeout)
        cancelPrepareTimeout()
        extTimeoutArmed = false
        Log.i(TAG, "Article engine setup retry requested")
        emitLocked()
        if (!extTimeoutArmed) {
            extTimeoutArmed = true
            main.postDelayed(extTimeout, ArticleConstants.EXT_TIMEOUT_MS)
        }
        return true
    }

    /** Re-asserts the active tab; call before each navigation. */
    fun setTabActive(session: GeckoSession) {
        try {
            runtime?.webExtensionController?.setTabActive(session, true)
        } catch (e: Exception) {
            Log.w(TAG, "setTabActive failed: ${e.javaClass.name}: ${e.message}")
        }
    }

    fun bpcVersion(): String? = bpcExt?.metaData?.version

    fun bpcUiState(): BpcUiState = coordinator.uiState()

    fun noteBpcObserved() {
        coordinator.noteInstalled(bpcExt?.metaData?.version)
    }

    /**
     * Runs one add-on update check. Automatic checks (`manual=false`) only
     * run while the engine is already in use — they never create the runtime.
     * Manual checks may start the runtime and install the known add-on first.
     */
    fun checkAddonUpdates(
        manual: Boolean,
        onDone: ((BpcUpdateCoordinator.CheckResult) -> Unit)? = null,
    ) {
        if (!manual && (runtime == null || bpcExt == null)) {
            onDone?.invoke(
                BpcUpdateCoordinator.CheckResult(
                    false,
                    BpcUpdatePolicy.Outcome.Keep(coordinator.uiState().installedVersion, null),
                ),
            )
            return
        }
        if (manual && (runtime == null || bpcExt == null)) {
            ensureForManualUpdate { prepareError ->
                if (prepareError != null) {
                    // The add-on was never prepared: finish the check visibly
                    // with the real error instead of running the updater.
                    coordinator.notePrepareFailure(prepareError)
                    val outcome = BpcUpdatePolicy.interpret(
                        coordinator.uiState().installedVersion, null, prepareError,
                    )
                    wrapUpdateDone(onDone)(
                        BpcUpdateCoordinator.CheckResult(false, outcome),
                    )
                    return@ensureForManualUpdate
                }
                coordinator.checkUpdates(manual = true, onDone = wrapUpdateDone(onDone))
            }
            return
        }
        coordinator.checkUpdates(manual = manual, onDone = wrapUpdateDone(onDone))
    }

    private fun wrapUpdateDone(
        onDone: ((BpcUpdateCoordinator.CheckResult) -> Unit)?,
    ): (BpcUpdateCoordinator.CheckResult) -> Unit = { result ->
        val warning = (result.outcome as? BpcUpdatePolicy.Outcome.Keep)?.warning
        if (warning != null) {
            main.post {
                listeners.toList().forEach {
                    try {
                        it.onUpdateWarning(warning)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        try {
            onDone?.invoke(result)
        } catch (e: Exception) {
            Log.w(TAG, "Add-on update callback failed: ${e.javaClass.name}: ${e.message}")
        }
    }

    /**
     * Manual path: creates the runtime and prepares the known add-on through
     * the same [prepareBpc] round the read path uses, then [then] with null
     * — or with the real prepare error when setup failed, in which case the
     * caller must finish visibly instead of running the updater.
     */
    private fun ensureForManualUpdate(then: (prepareError: String?) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { ensureForManualUpdate(then) }
            return
        }
        try {
            val rt = ensureRuntime()
            if (!startup.needsPrepare()) {
                then(startup.snapshot().error)
                return
            }
            prepareBpc(rt.webExtensionController, then)
        } catch (e: Exception) {
            val detail = "Manual add-on prepare failed: ${e.javaClass.name}: ${e.message}"
            Log.w(TAG, detail)
            then(detail)
        }
    }

    /**
     * One shared BPC prepare round (read and manual paths alike): lists the
     * installed add-ons, installs the bundled copy only when missing, or
     * enables a disabled known copy. Observed metadata, enabling, and the
     * readiness gate reconcile through [startup] identically for both
     * callers; listing alone never marks readiness. Concurrent callers join
     * the live round instead of superseding its callbacks. [onSettled] fires
     * exactly once with null when the known copy is adopted, or with the
     * real error when this round failed (a null callback means
     * listener-driven only). Every round is bounded by the prepare watchdog,
     * including settings-only checks that never arm the readiness timeout.
     */
    private fun prepareBpc(
        controller: WebExtensionController,
        onSettled: ((String?) -> Unit)?,
    ) {
        val waiter: ((String?) -> Unit)? = onSettled?.let { callback ->
            { error: String? ->
                if (error != null) {
                    Log.w(TAG, "Article extension setup failed: $error")
                }
                try {
                    callback(error)
                } catch (e: Exception) {
                    Log.w(TAG, "Setup settle callback failed: ${e.javaClass.name}: ${e.message}")
                }
            }
        }
        val attempt = when (val join = startup.joinPrepare(waiter)) {
            is BpcStartup.PrepareJoin.Started -> join.id
            is BpcStartup.PrepareJoin.Joined -> {
                if (prepareTimeoutTask == null) armPrepareTimeout(join.id)
                emitLocked()
                return
            }
        }
        armPrepareTimeout(attempt)
        emitLocked()
        val settle: (String?) -> Unit = { settleAttempt(attempt, it) }
        try {
            controller.list().accept(
                { list ->
                    // GeckoResult callbacks are any-thread; Gecko APIs need main.
                    main.post { onListResult(controller, attempt, list, settle) }
                },
                { e ->
                    main.post { onListFailed(attempt, e, settle) }
                },
            )
        } catch (e: Exception) {
            if (startup.onListFailed(attempt, "Extension list() failed: ${e.javaClass.name}: ${e.message}")) {
                settle(startup.snapshot().error)
            } else {
                settle("Article extension setup was superseded by a newer attempt")
            }
        }
    }

    /**
     * Settles one prepare round and refreshes listeners. Closing the live
     * round also disarms its watchdog; stale settles only release orphaned
     * waiters and never touch live state.
     */
    private fun settleAttempt(attempt: Int, error: String?) {
        if (startup.settlePrepare(attempt, error)) {
            cancelPrepareTimeout()
        }
        emitLocked()
    }

    /** Arms the bounded prepare watchdog for one round (read+manual alike). */
    private fun armPrepareTimeout(attempt: Int) {
        cancelPrepareTimeout()
        val task = Runnable { onPrepareTimeout(attempt) }
        prepareTimeoutTask = task
        main.postDelayed(task, ArticleConstants.EXT_TIMEOUT_MS)
    }

    private fun cancelPrepareTimeout() {
        prepareTimeoutTask?.let { main.removeCallbacks(it) }
        prepareTimeoutTask = null
    }

    /**
     * A hung list/install/enable fails visibly: manual waiters get the actual
     * timeout, and the read path shows a retryable error. Settled rounds
     * disarm this, so a successful manual check never fails spuriously later;
     * capture readiness is never consulted here (settings-only checks don't
     * prepare capture).
     */
    private fun onPrepareTimeout(attempt: Int) {
        prepareTimeoutTask = null
        val snap = startup.snapshot()
        val detail = "Extension setup timed out after ${ArticleConstants.EXT_TIMEOUT_MS}ms " +
            "(known=${snap.known} enabled=${snap.enabledObserved} ready=${snap.readyObserved})"
        if (startup.timeoutPrepare(attempt, detail)) {
            main.removeCallbacks(extTimeout)
            Log.w(TAG, "Article engine failed: $detail")
        }
        emitLocked()
    }

    private fun onListResult(
        controller: WebExtensionController,
        attempt: Int,
        list: List<WebExtension>?,
        settle: (String?) -> Unit,
    ) {
        val found = list?.firstOrNull { it.id == ArticleConstants.BPC_ID }
        val enabled = found?.metaData?.enabled == true
        when (startup.onListed(attempt, found != null, enabled, found?.metaData?.version)) {
            BpcStartup.ListedAction.STALE -> {
                Log.w(TAG, "Ignoring stale BPC list result (attempt $attempt)")
                settle("Article extension setup was superseded by a newer attempt")
                return
            }
            BpcStartup.ListedAction.INSTALL_MISSING -> {
                installBpc(controller, attempt, settle)
                return
            }
            BpcStartup.ListedAction.ENABLE_FOUND -> {
                Log.i(TAG, "BPC present v${found?.metaData?.version} disabled; enabling")
                adoptBpcRef(found!!)
                enableFound(controller, attempt, found, settle)
                settle(null)
                return
            }
            BpcStartup.ListedAction.WAIT_READY -> {
                // Installed + enabled. Readiness is still asynchronous: wait
                // for the observed onReady before any navigation, even when
                // the version already matches.
                Log.i(TAG, "BPC v${found?.metaData?.version} enabled; waiting for observed ready")
                adoptBpcRef(found!!)
                settle(null)
                return
            }
        }
    }

    private fun onListFailed(
        attempt: Int,
        e: Throwable?,
        settle: (String?) -> Unit,
    ) {
        // A failed list is a failure with its real error — never a silent
        // fall-through into an install that hides what broke.
        if (startup.onListFailed(attempt, "Extension list() failed: ${describeError(e)}")) {
            settle(startup.snapshot().error)
        } else {
            Log.w(TAG, "Ignoring stale BPC list failure (attempt $attempt)")
            settle("Article extension setup was superseded by a newer attempt")
        }
    }

    /** Stashes the known add-on reference, tab delegate, and version note. */
    private fun adoptBpcRef(ext: WebExtension) {
        bpcExt = ext
        ext.setTabDelegate(bpcTabDelegate)
        coordinator.noteInstalled(ext.metaData?.version)
    }

    private fun enableFound(
        controller: WebExtensionController,
        attempt: Int,
        ext: WebExtension,
        settle: (String?) -> Unit,
    ) {
        try {
            controller.enable(ext, WebExtensionController.EnableSource.APP).accept(
                { updated ->
                    main.post {
                        val enabledExt = updated?.takeIf { it.metaData?.enabled == true }
                        if (!startup.onEnableResult(attempt, enabledExt != null, updated?.metaData?.version)) {
                            Log.w(TAG, "Ignoring stale BPC enable result (attempt $attempt)")
                            settle("Article extension setup was superseded by a newer attempt")
                            return@post
                        }
                        if (enabledExt != null) {
                            adoptBpcRef(enabledExt)
                            Log.i(TAG, "BPC enabled v${enabledExt.metaData?.version}; waiting for ready")
                        }
                        settle(startup.snapshot().error)
                    }
                },
                { e ->
                    main.post {
                        val detail = "Article extension enable() failed: ${describeError(e)}"
                        if (startup.onEnableFailed(attempt, detail)) {
                            settle(startup.snapshot().error)
                        } else {
                            Log.w(TAG, "Ignoring stale BPC enable failure (attempt $attempt)")
                            settle("Article extension setup was superseded by a newer attempt")
                        }
                    }
                },
            )
        } catch (e: Exception) {
            val detail = "Article extension enable() failed: ${e.javaClass.name}: ${e.message}"
            if (startup.onEnableFailed(attempt, detail)) {
                settle(startup.snapshot().error)
            } else {
                settle("Article extension setup was superseded by a newer attempt")
            }
        }
    }

    /** Enables an updated-but-disabled copy; failures surface via listeners. */
    private fun enableUpdated(ext: WebExtension) {
        val controller = runtime?.webExtensionController ?: return
        val attempt = startup.beginAttempt()
        try {
            controller.enable(ext, WebExtensionController.EnableSource.APP).accept(
                { updated ->
                    main.post {
                        val enabledExt = updated?.takeIf { it.metaData?.enabled == true }
                        if (!startup.onEnableResult(attempt, enabledExt != null, updated?.metaData?.version)) {
                            Log.w(TAG, "Ignoring stale BPC update-enable result (attempt $attempt)")
                            return@post
                        }
                        if (enabledExt != null) {
                            adoptBpcRef(enabledExt)
                            Log.i(TAG, "BPC update enabled v${enabledExt.metaData?.version}; waiting for ready")
                        }
                        emitLocked()
                    }
                },
                { e ->
                    main.post {
                        if (startup.onEnableFailed(
                                attempt,
                                "Article extension enable() failed: ${describeError(e)}",
                            )
                        ) {
                            emitLocked()
                        } else {
                            Log.w(TAG, "Ignoring stale BPC update-enable failure (attempt $attempt)")
                        }
                    }
                },
            )
        } catch (e: Exception) {
            if (startup.onEnableFailed(
                    attempt,
                    "Article extension enable() failed: ${e.javaClass.name}: ${e.message}",
                )
            ) {
                emitLocked()
            }
        }
    }

    private fun runEngineUpdate(
        @Suppress("UNUSED_PARAMETER") installedVersion: String?,
        onDone: (BpcUpdateCoordinator.UpdateResult) -> Unit,
    ) {
        main.post {
            val rt = runtime
            val bpc = bpcExt
            if (rt == null || bpc == null || bpc.id != ArticleConstants.BPC_ID) {
                onDone(BpcUpdateCoordinator.UpdateResult(null, "article extension is not installed yet"))
                return@post
            }
            val pending: GeckoResult<WebExtension>? = try {
                rt.webExtensionController.update(bpc)
            } catch (e: Exception) {
                onDone(
                    BpcUpdateCoordinator.UpdateResult(
                        null, "${e.javaClass.name}: ${e.message}",
                    ),
                )
                return@post
            }
            if (pending == null) {
                // No update available: success, keep current.
                onDone(BpcUpdateCoordinator.UpdateResult(null, null))
                return@post
            }
            pending.accept(
                { updated ->
                    main.post {
                        val (version, mappingError) = BpcUpdatePolicy.mapEngineUpdate(
                            ArticleConstants.BPC_ID,
                            updated?.id,
                            updated?.metaData?.version,
                            null,
                        )
                        if (mappingError != null || version == null || updated == null) {
                            // No update (success, keep current) or a wrong-id
                            // error: either way the known-good add-on and its
                            // observed readiness stay exactly as they are.
                            onDone(BpcUpdateCoordinator.UpdateResult(null, mappingError))
                            return@post
                        }
                        val enabled = updated.metaData?.enabled == true
                        adoptBpcRef(updated)
                        startup.onUpdateApplied(version, enabled)
                        Log.i(TAG, "BPC updated to v$version enabled=$enabled; waiting for ready")
                        emitLocked()
                        if (!enabled) {
                            // An updated-but-disabled copy can never be ready:
                            // enable it like a fresh disabled install.
                            enableUpdated(updated)
                        }
                        onDone(BpcUpdateCoordinator.UpdateResult(version, null))
                    }
                },
                { e ->
                    main.post {
                        // Update failures never touch the known-good add-on
                        // or its observed readiness; reads keep working.
                        onDone(BpcUpdateCoordinator.UpdateResult(null, describeError(e)))
                    }
                },
            )
        }
    }

    // ------------------------------------------------------------ runtime ---

    private fun ensureRuntime(): GeckoRuntime {
        runtime?.let { return it }
        val rt = GeckoRuntime.create(app)
        runtime = rt
        if (!delegatesSet) {
            delegatesSet = true
            rt.webExtensionController.setAddonManagerDelegate(addonManagerDelegate)
            rt.webExtensionController.setPromptDelegate(promptDelegate)
        }
        Log.i(TAG, "Article engine runtime created (GeckoView ${ArticleConstants.GECKOVIEW_VERSION})")
        return rt
    }

    private fun ensureCapture(
        controller: WebExtensionController,
        session: GeckoSession,
        messageDelegate: WebExtension.MessageDelegate,
    ) {
        controller.ensureBuiltIn(
            ArticleConstants.CAPTURE_LOCATION,
            ArticleConstants.CAPTURE_ID,
        ).accept(
            { ext ->
                // GeckoResult callbacks are any-thread; Gecko APIs need main.
                main.post {
                    if (ext == null || ext.id != ArticleConstants.CAPTURE_ID) {
                        fail("Capture helper id mismatch: ${ext?.id}")
                        return@post
                    }
                    // A stale helper install (older Round's content script)
                    // cannot speak the current protocol: reinstall it once
                    // from the bundled copy instead of running mismatched.
                    val installedVersion = ext.metaData?.version
                    if (installedVersion != null &&
                        installedVersion != ArticleConstants.CAPTURE_VERSION
                    ) {
                        Log.i(
                            TAG,
                            "Capture helper stale v$installedVersion; " +
                                "reinstalling v${ArticleConstants.CAPTURE_VERSION}",
                        )
                        reinstallCapture(controller, session, messageDelegate, ext)
                        return@post
                    }
                    adoptCapture(session, messageDelegate, ext)
                }
            },
            { e ->
                fail("Capture helper failed: ${e?.javaClass?.simpleName}: ${e?.message}")
            },
        )
    }

    /** Uninstalls a stale helper, then force-installs the bundled copy. */
    private fun reinstallCapture(
        controller: WebExtensionController,
        session: GeckoSession,
        messageDelegate: WebExtension.MessageDelegate,
        stale: WebExtension,
    ) {
        try {
            controller.uninstall(stale).accept(
                {
                    main.post {
                        try {
                            controller.installBuiltIn(ArticleConstants.CAPTURE_LOCATION).accept(
                                { ext ->
                                    main.post {
                                        if (ext == null || ext.id != ArticleConstants.CAPTURE_ID) {
                                            fail("Capture helper reinstall id mismatch: ${ext?.id}")
                                            return@post
                                        }
                                        adoptCapture(session, messageDelegate, ext)
                                    }
                                },
                                { e ->
                                    fail("Capture helper reinstall failed: ${describeError(e)}")
                                },
                            )
                        } catch (e: Exception) {
                            fail("Capture helper reinstall failed: ${e.javaClass.name}: ${e.message}")
                        }
                    }
                },
                { e ->
                    fail("Capture helper uninstall failed: ${describeError(e)}")
                },
            )
        } catch (e: Exception) {
            fail("Capture helper uninstall failed: ${e.javaClass.name}: ${e.message}")
        }
    }

    private fun adoptCapture(
        session: GeckoSession,
        messageDelegate: WebExtension.MessageDelegate,
        ext: WebExtension,
    ) {
        try {
            session.webExtensionController.setMessageDelegate(
                ext, messageDelegate, ArticleConstants.NATIVE_APP,
            )
        } catch (e: Exception) {
            fail("Capture helper attach failed: ${e.javaClass.name}: ${e.message}")
            return
        }
        captureExt = ext
        captureOk = true
        Log.i(TAG, "Capture helper ready (${ext.id} v${ext.metaData?.version})")
        emitLocked()
        maybeAutoUpdate()
    }

    private fun installBpc(
        controller: WebExtensionController,
        attempt: Int,
        settle: (String?) -> Unit,
    ) {
        // Set BEFORE install(): onEnabled may arrive before install() returns,
        // and must never navigate without observed readiness.
        startup.noteInstallStarted()
        emitLocked()
        val staged: File
        try {
            staged = stageBpcXpi()
        } catch (e: Exception) {
            val detail = "Article extension staging failed: ${e.message}"
            if (startup.onInstallFailed(attempt, detail)) {
                settle(startup.snapshot().error)
            } else {
                settle("Article extension setup was superseded by a newer attempt")
            }
            return
        }
        try {
            controller.install(
                "file://${staged.absolutePath}",
                WebExtensionController.INSTALLATION_METHOD_FROM_FILE,
            ).accept(
                { ext ->
                    main.post {
                        if (ext == null || ext.id != ArticleConstants.BPC_ID) {
                            val detail = "Article extension install id mismatch: ${ext?.id}"
                            if (startup.onInstallFailed(attempt, detail)) {
                                settle(startup.snapshot().error)
                            } else {
                                Log.w(TAG, "Ignoring stale BPC install result (attempt $attempt)")
                                settle("Article extension setup was superseded by a newer attempt")
                            }
                            return@post
                        }
                        if (!startup.onInstalled(
                                attempt,
                                ext.metaData?.enabled == true,
                                ext.metaData?.version,
                            )
                        ) {
                            Log.w(TAG, "Ignoring stale BPC install result (attempt $attempt)")
                            settle("Article extension setup was superseded by a newer attempt")
                            return@post
                        }
                        adoptBpcRef(ext)
                        val md = ext.metaData
                        Log.i(
                            TAG,
                            "BPC install() id=${ext.id} v${md?.version} enabled=${md?.enabled} " +
                                "signedState=${md?.signedState}",
                        )
                        settle(null)
                    }
                },
                { e ->
                    main.post {
                        val detail = "Article extension install() failed: ${describeError(e)}"
                        if (startup.onInstallFailed(attempt, detail)) {
                            settle(startup.snapshot().error)
                        } else {
                            Log.w(TAG, "Ignoring stale BPC install failure (attempt $attempt)")
                            settle("Article extension setup was superseded by a newer attempt")
                        }
                    }
                },
            )
        } catch (e: Exception) {
            val detail = "Article extension install() failed: ${e.javaClass.name}: ${e.message}"
            if (startup.onInstallFailed(attempt, detail)) {
                settle(startup.snapshot().error)
            } else {
                settle("Article extension setup was superseded by a newer attempt")
            }
        }
    }

    private fun stageBpcXpi(): File {
        val dir = File(app.filesDir, "bpc").apply { mkdirs() }
        val out = File(dir, "bpc.xpi")
        app.assets.open(ArticleConstants.BPC_XPI_ASSET).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        out.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (!hex.equals(ArticleConstants.BPC_XPI_SHA256, ignoreCase = true)) {
            out.delete()
            throw IllegalStateException("SHA-256 mismatch (got $hex)")
        }
        return out
    }

    /** Daily automatic check once the engine is in use (throttled inside). */
    private fun maybeAutoUpdate() {
        if (runtime == null || bpcExt == null) return
        if (!ArticleReadiness.canNavigate(readinessState())) return
        checkAddonUpdates(manual = false, onDone = null)
    }

    // ------------------------------------------------------------- state ---

    private fun readinessState(): ArticleReadiness.State {
        val snap = startup.snapshot()
        return ArticleReadiness.State(
            captureHelperReady = captureOk,
            bpcKnown = snap.known,
            bpcEnabled = snap.enabledObserved,
            bpcReady = snap.readyObserved,
            installPending = snap.installPending,
        )
    }

    private fun emitLocked() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { emitLocked() }
            return
        }
        val state = readinessState()
        val can = ArticleReadiness.canNavigate(state)
        if (can) {
            main.removeCallbacks(extTimeout)
        }
        val error = startup.snapshot().error
        val reason = error ?: ArticleReadiness.waitReason(state)
        val version = bpcExt?.metaData?.version
        listeners.toList().forEach {
            try {
                it.onReadiness(can, reason, version, error)
            } catch (_: Exception) {
            }
        }
        if (can) {
            maybeAutoUpdate()
        }
    }

    private fun fail(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { fail(reason) }
            return
        }
        if (startup.snapshot().error != null) return
        startup.failNow(reason)
        main.removeCallbacks(extTimeout)
        Log.w(TAG, "Article engine failed: $reason")
        emitLocked()
    }

    private fun describeError(e: Throwable?): String {
        if (e is WebExtension.InstallException) {
            return "InstallException code=${installErrorName(e.code)} " +
                "id=${e.extensionId} version=${e.extensionVersion}"
        }
        return "${e?.javaClass?.simpleName}: ${e?.message}"
    }

    // ----------------------------------------------------------- delegates ---

    private val addonManagerDelegate = object : WebExtensionController.AddonManagerDelegate {
        private fun isBpc(ext: WebExtension) = ext.id == ArticleConstants.BPC_ID

        override fun onEnabled(extension: WebExtension) {
            if (!isBpc(extension)) return
            // Delegate callbacks are any-thread; Gecko APIs need main.
            main.post {
                startup.onEnabledEvent(extension.metaData?.version)
                if (bpcExt == null) {
                    adoptBpcRef(extension)
                } else {
                    coordinator.noteInstalled(extension.metaData?.version)
                }
                emitLocked()
            }
        }

        override fun onReady(extension: WebExtension) {
            if (!isBpc(extension)) return
            main.post {
                startup.onReadyEvent(extension.metaData?.version)
                coordinator.noteInstalled(extension.metaData?.version)
                emitLocked()
            }
        }

        override fun onDisabled(extension: WebExtension) {
            if (!isBpc(extension)) return
            main.post {
                startup.onDisabledEvent()
                main.removeCallbacks(extTimeout)
                Log.w(TAG, "Article engine failed: ${startup.snapshot().error}")
                emitLocked()
            }
        }

        override fun onInstallationFailed(
            extension: WebExtension?,
            installException: WebExtension.InstallException,
        ) {
            if (extension == null || !isBpc(extension)) return
            // Only a fresh install in flight can fail this way: the matching
            // install() rejection already records it attempt-scoped, so this
            // stray event only matters when nothing recorded it yet. It must
            // never poison an adopted add-on (e.g. an update failure, which
            // the update path reports itself) or a retried attempt.
            main.post {
                val snap = startup.snapshot()
                if (!snap.known && snap.installPending && snap.error == null) {
                    fail("Article extension install failed: ${describeError(installException)}")
                }
            }
        }
    }

    private val promptDelegate = object : WebExtensionController.PromptDelegate {
        override fun onInstallPromptRequest(
            extension: WebExtension,
            permissions: Array<String>,
            origins: Array<String>,
            dataCollectionPermissions: Array<String>,
        ): GeckoResult<WebExtension.PermissionPromptResponse> {
            val known = extension.id == ArticleConstants.BPC_ID
            Log.i(
                TAG,
                "Add-on install prompt id=${extension.id} known=$known " +
                    "permissions=${permissions.size} origins=${origins.size}",
            )
            return GeckoResult.fromValue(
                WebExtension.PermissionPromptResponse(known, false, false),
            )
        }

        override fun onUpdatePrompt(
            extension: WebExtension,
            newPermissions: Array<String>,
            newOrigins: Array<String>,
            newDataCollectionPermissions: Array<String>,
        ): GeckoResult<AllowOrDeny> {
            val known = extension.id == ArticleConstants.BPC_ID
            return if (known) GeckoResult.allow() else GeckoResult.deny()
        }

        override fun onOptionalPrompt(
            extension: WebExtension,
            permissions: Array<String>,
            origins: Array<String>,
            dataCollectionPermissions: Array<String>,
        ): GeckoResult<AllowOrDeny> {
            val known = extension.id == ArticleConstants.BPC_ID
            return if (known) GeckoResult.allow() else GeckoResult.deny()
        }
    }

    private val bpcTabDelegate = object : WebExtension.TabDelegate {
        override fun onNewTab(
            source: WebExtension,
            createDetails: WebExtension.CreateTabDetails,
        ): GeckoResult<GeckoSession>? {
            Log.i(TAG, "Add-on new-tab request declined (single-view article engine)")
            return null
        }

        override fun onOpenOptionsPage(source: WebExtension) {
        }
    }
}
