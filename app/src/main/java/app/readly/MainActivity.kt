package app.readly

import android.Manifest
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import app.readly.model.InstallStage
import app.readly.model.VoiceCatalog
import app.readly.model.VoiceFamily
import app.readly.model.VoiceModelStore
import app.readly.playback.ReaderService
import app.readly.playback.ReaderSnapshot
import app.readly.playback.ReaderState
import app.readly.playback.ReaderStatus
import app.readly.text.SourceFormat
import app.readly.ui.AddContentSheets
import app.readly.ui.HomeBrowser
import java.io.File
import kotlin.math.roundToInt

/**
 * First-run voice download, then Home: the Android Auto layout (Websites, Latest,
 * Library and Recents tabs, see [HomeBrowser]) with a clean top bar (Search,
 * Refresh, Settings) and a floating `+` menu for files, pasted text, and adding
 * websites. Playback state arrives from [ReaderState], so rotation never loses it;
 * all preferences live in [SettingsActivity].
 */
class MainActivity : Activity(), InstallTask.Listener {

    private lateinit var store: VoiceModelStore
    private lateinit var prefs: SharedPreferences
    private var task: InstallTask? = null
    private var lastSetupError: Exception? = null
    private var installingPackId: String? = null
    private val setupSamples = SamplePlayer()
    private var setupPlaying = false
    private var setupLoading = false

    private lateinit var setupContainer: LinearLayout
    private lateinit var readerContainer: LinearLayout
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var setupEstimate: TextView
    private lateinit var setupSampleButton: Button
    private lateinit var chooseVoiceButton: Button
    private lateinit var downloadButton: Button
    private lateinit var downloadHelp: TextView
    private lateinit var pocketButton: Button
    private lateinit var pocketHelp: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressLabel: TextView
    private lateinit var cancelButton: Button
    private lateinit var cancelHelp: TextView
    private lateinit var errorText: TextView
    private lateinit var retryButton: Button
    private lateinit var retryHelp: TextView

    private lateinit var notifRow: LinearLayout
    private lateinit var notifNote: TextView
    private lateinit var allowButton: Button
    private lateinit var headerTitle: TextView
    private lateinit var searchButton: ImageButton
    private lateinit var refreshButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var homeFab: ImageButton
    private lateinit var noticeCard: LinearLayout
    private lateinit var noticeText: TextView
    private lateinit var dismissNotice: ImageButton
    private lateinit var errorCard: LinearLayout
    private lateinit var errorMessage: TextView
    private lateinit var openPageButton: Button
    private lateinit var openPageHelp: TextView
    private lateinit var needVoiceBanner: TextView
    private lateinit var miniBar: LinearLayout
    private lateinit var miniText: TextView
    private lateinit var miniToggle: ImageButton
    private lateinit var miniDetail: TextView
    private lateinit var miniProgress: ProgressBar

    /** Ticks the loading step's elapsed time once a second while a step is running. */
    private val stepTicker = object : Runnable {
        override fun run() {
            updateMiniBar(ReaderState.snapshot)
        }
    }
    private lateinit var homeRefresh: SwipeRefreshLayout
    private lateinit var homeHeader: LinearLayout
    private lateinit var homeTabs: LinearLayout
    private lateinit var home: HomeBrowser
    private var returningFromSettings = false

    private val readerListener = ReaderState.Listener { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupContainer = findViewById(R.id.setupContainer)
        readerContainer = findViewById(R.id.readerContainer)
        title = findViewById(R.id.title)
        body = findViewById(R.id.body)
        setupEstimate = findViewById(R.id.setupEstimate)
        setupSampleButton = findViewById(R.id.setupSampleButton)
        chooseVoiceButton = findViewById(R.id.chooseVoiceButton)
        downloadButton = findViewById(R.id.downloadButton)
        downloadHelp = findViewById(R.id.downloadHelp)
        pocketButton = findViewById(R.id.pocketButton)
        pocketHelp = findViewById(R.id.pocketHelp)
        progressBar = findViewById(R.id.progressBar)
        progressLabel = findViewById(R.id.progressLabel)
        cancelButton = findViewById(R.id.cancelButton)
        cancelHelp = findViewById(R.id.cancelHelp)
        errorText = findViewById(R.id.errorText)
        retryButton = findViewById(R.id.retryButton)
        retryHelp = findViewById(R.id.retryHelp)

        notifRow = findViewById(R.id.notifRow)
        notifNote = findViewById(R.id.notifNote)
        allowButton = findViewById(R.id.allowButton)
        headerTitle = findViewById(R.id.headerTitle)
        searchButton = findViewById(R.id.searchButton)
        refreshButton = findViewById(R.id.refreshButton)
        settingsButton = findViewById(R.id.settingsButton)
        homeFab = findViewById(R.id.homeFab)
        noticeCard = findViewById(R.id.noticeCard)
        noticeText = findViewById(R.id.noticeText)
        dismissNotice = findViewById(R.id.dismissNotice)
        errorCard = findViewById(R.id.errorCard)
        errorMessage = findViewById(R.id.errorMessage)
        openPageButton = findViewById(R.id.openPageButton)
        openPageHelp = findViewById(R.id.openPageHelp)
        miniBar = findViewById(R.id.miniBar)
        miniText = findViewById(R.id.miniText)
        miniToggle = findViewById(R.id.miniToggle)
        miniDetail = findViewById(R.id.miniDetail)
        miniProgress = findViewById(R.id.miniBarProgress)
        needVoiceBanner = findViewById(R.id.needVoiceBanner)
        homeRefresh = findViewById(R.id.homeRefresh)
        homeRefresh.setColorSchemeColors(getColor(R.color.accent))
        homeRefresh.setProgressBackgroundColorSchemeColor(getColor(R.color.surface2))
        homeRefresh.setOnRefreshListener { home.refresh() }
        refreshButton.setOnClickListener { home.refresh() }
        homeHeader = findViewById(R.id.readerHeader)
        homeTabs = findViewById(R.id.tabRow)
        home = HomeBrowser(
            this,
            tabRow = homeTabs,
            onNavigate = { findViewById<ScrollView>(R.id.homeScroll).scrollTo(0, 0) },
            onRefreshingChanged = { refreshing ->
                homeRefresh.isRefreshing = refreshing
                refreshButton.isEnabled = !refreshing && readerContainer.visibility == View.VISIBLE
                if (refreshing) homeRefresh.announceForAccessibility(getString(R.string.feed_refreshing))
            },
            onTabTitleChanged = { tabTitle ->
                if (tabTitle.isNotBlank()) headerTitle.text = tabTitle
            },
            header = findViewById(R.id.browseHeader),
            back = findViewById(R.id.browseBack),
            headerTitle = findViewById(R.id.browseTitle),
            content = findViewById(R.id.browseContent),
        ) {
            openManageWebsites()
        }
        if (intent?.getBooleanExtra(EXTRA_NEED_VOICE, false) == true) {
            needVoiceBanner.visibility = View.VISIBLE
        }

        val pack = ReaderService.activePack(this)
        store = VoiceModelStore(pack, File(noBackupFilesDir, "voices"), VoiceModelStore::httpUrlOpener)
        prefs = getSharedPreferences(ReaderService.PREFS_NAME, MODE_PRIVATE)
        body.text = getString(R.string.setup_body, setupVoiceName(), pack.sizeMb)
        downloadButton.text = getString(R.string.download_button, pack.sizeMb)
        updateSetupEstimate(pack.sizeMb)
        val pocket = VoiceCatalog.byId(POCKET_PACK_ID)!!
        pocketButton.text = getString(R.string.persian_only_button, pocket.sizeMb)

        setupSampleButton.setOnClickListener { toggleSetupSample() }
        chooseVoiceButton.setOnClickListener {
            startActivity(Intent(this, VoicesActivity::class.java))
            overridePendingTransition(R.anim.screen_slide_in, R.anim.screen_slide_out)
        }
        downloadButton.setOnClickListener { startInstall(store) }
        pocketButton.setOnClickListener {
            startInstall(VoiceModelStore(pocket, voicesDir(), VoiceModelStore::httpUrlOpener))
        }
        retryButton.setOnClickListener {
            startInstall(setupTarget(installingPackId ?: store.pack.id))
        }
        cancelButton.setOnClickListener { task?.cancel() }

        allowButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIF_REQUEST,
                )
            }
        }
        searchButton.setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
            overridePendingTransition(R.anim.screen_slide_in, R.anim.screen_slide_out)
        }
        settingsButton.setOnClickListener {
            returningFromSettings = true
            SettingsActivity.open(this)
        }
        homeFab.setOnClickListener {
            AddContentSheets.showAddMenu(
                this,
                onOpenFile = ::openFile,
                onPasteText = ::openPasteSheet,
                onAddWebsite = ::openManageWebsites,
            )
        }
        dismissNotice.setOnClickListener {
            ReaderState.update { it.copy(notice = null) }
        }
        openPageButton.setOnClickListener {
            // The exact failed link from state: works even when the input
            // box is empty (e.g. a failed shared link). Retries in the
            // automatic article engine, not the legacy manual browser.
            ReaderState.snapshot.retryUrl?.let {
                startActivity(ArticleActivity.intent(this, it))
                overridePendingTransition(R.anim.screen_slide_in, R.anim.screen_slide_out)
            }
        }
        miniBar.setOnClickListener {
            startActivity(Intent(this, NowPlayingActivity::class.java))
            overridePendingTransition(R.anim.screen_slide_in, R.anim.screen_slide_out)
        }
        miniToggle.setOnClickListener { sendAction(ReaderService.ACTION_TOGGLE) }
        if (VoiceModelStore.anyInstalled(voicesDir())) {
            showReader()
        } else {
            // Re-attach to the running install after rotation instead of restarting it
            // (only when it is ours: the main voice or Pocket; another pack's
            // install belongs to its own screen).
            val running = (application as ReadlyApp).installTask
            if (running != null && !running.isDone && isSetupPack(running.packId)) {
                task = running
                installingPackId = running.packId
                running.listener = this
                showProgress()
                updateProgress(running.lastStage, running.lastDoneBytes, running.lastTotalBytes)
            } else {
                showIdle()
            }
        }
        render(ReaderState.snapshot)
    }

    override fun onStart() {
        super.onStart()
        ReaderState.addListener(readerListener)
        home.start()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!home.onBack()) {
            super.onBackPressed()
        }
    }

    private fun openManageWebsites() {
        returningFromSettings = true
        startActivity(Intent(this, FeedsActivity::class.java))
        overridePendingTransition(R.anim.screen_slide_in, R.anim.screen_slide_out)
    }

    private fun openPasteSheet() {
        AddContentSheets.showPasteDialog(this) { text -> routePastedText(text) }
    }

    @Suppress("DEPRECATION")
    private fun openFile() {
        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                        "application/pdf", "application/x-pdf", "image/*", "text/*",
                        "application/xhtml+xml", "application/octet-stream",
                    ))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                FILE_REQUEST,
            )
        } catch (error: Exception) {
            Log.e("Readly", "Couldn't open the document picker", error)
            Toast.makeText(this, R.string.open_file_picker_failed, Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Activity result API; this app uses platform activities")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != FILE_REQUEST || resultCode == RESULT_CANCELED) return
        try {
            check(resultCode == RESULT_OK) { "Document picker returned an unexpected result: $resultCode" }
            val uri = data?.data ?: error("Document picker returned no file")
            // The picker and Android sharing enter the same validated import/read pipeline.
            // Forward the temporary grant; PDFs and images are copied into private storage.
            startActivity(ShareActivity.fileIntent(this, uri))
        } catch (error: Exception) {
            Log.e("Readly", "Couldn't open the selected document", error)
            Toast.makeText(this, R.string.open_file_failed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        task?.let { if (it.listener === this) it.listener = null }
        super.onPause()
    }

    override fun onStop() {
        ReaderState.removeListener(readerListener)
        miniBar.removeCallbacks(stepTicker)
        home.stop()
        setupSamples.release()
        setupPlaying = false
        setupLoading = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // The active pack may have changed on the Voices screen while we were away.
        // On a fresh install the active pack is the recommended one (DEFAULT_ID).
        val pack = ReaderService.activePack(this)
        if (pack.id != store.pack.id) {
            task = null
            lastSetupError = null
            installingPackId = null
            store = VoiceModelStore(pack, File(noBackupFilesDir, "voices"), VoiceModelStore::httpUrlOpener)
            body.text = getString(R.string.setup_body, setupVoiceName(), pack.sizeMb)
            downloadButton.text = getString(R.string.download_button, pack.sizeMb)
            updateSetupEstimate(pack.sizeMb)
        }
        repaintSetupSampleButton()
        syncSetupState()
        if (returningFromSettings) {
            returningFromSettings = false
            home.refreshLayout()
        }
        // Warm the engine while the user pastes so the first READ starts instantly.
        if (store.isInstalled() && ReaderState.snapshot.status == ReaderStatus.IDLE) {
            ReaderService.preload(this)
        }
        updateNotifRow()
    }

    /**
     * Shows reader, progress, error or idle from the current disk state: an install
     * that finished on the Voices screen while we were away lands here, as does a
     * re-attach to our still-running install.
     */
    private fun syncSetupState() {
        if (VoiceModelStore.anyInstalled(voicesDir())) {
            showReader()
            return
        }
        val running = (application as ReadlyApp).installTask
        if (running != null && !running.isDone && isSetupPack(running.packId)) {
            task = running
            installingPackId = running.packId
            running.listener = this
            showProgress()
            updateProgress(running.lastStage, running.lastDoneBytes, running.lastTotalBytes)
            return
        }
        task = null
        val error = lastSetupError
        if (error != null) {
            errorText.text = getString(
                R.string.error_format,
                error.javaClass.name,
                error.message ?: "(no details)",
            )
            showError()
        } else {
            showIdle()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        if (requestCode != NOTIF_REQUEST) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            return
        }
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            notifRow.visibility = View.GONE
        } else {
            // Keep the note visible: reading works, notification controls don't.
            notifNote.setText(R.string.notif_note_denied)
            notifRow.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        home.close()
        task?.let { if (it.listener === this) it.listener = null }
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Home
    // ------------------------------------------------------------------

    private fun routePastedText(text: String): Boolean {
        if (text.isBlank()) return false
        return when (val route = app.readly.article.ArticleRoute.routeInput(text)) {
            is app.readly.article.ArticleRoute.Decision.Article -> {
                startActivity(ArticleActivity.intent(this, route.url))
                overridePendingTransition(R.anim.screen_slide_in, R.anim.screen_slide_out)
                true
            }
            is app.readly.article.ArticleRoute.Decision.Prose -> {
                ReaderService.read(this, route.text, route.title, SourceFormat.PLAIN)
                startActivity(Intent(this, NowPlayingActivity::class.java))
                overridePendingTransition(R.anim.screen_slide_in, R.anim.screen_slide_out)
                true
            }
            null -> false
        }
    }

    private fun sendAction(action: String) {
        startService(Intent(this, ReaderService::class.java).setAction(action))
    }

    /** The reading notices render as one dismissible card above the voice chips. */
    private fun render(snap: ReaderSnapshot) {
        if (!snap.notice.isNullOrBlank()) {
            noticeCard.visibility = View.VISIBLE
            noticeText.text = snap.notice
        } else {
            noticeCard.visibility = View.GONE
        }
        renderError(snap)
        updateMiniBar(snap)
    }

    /**
     * Failed readings show the real error; a failed eligible link
     * additionally offers a retry in the automatic article engine.
     */
    private fun renderError(snap: ReaderSnapshot) {
        if (snap.status == ReaderStatus.ERROR && !snap.errorMessage.isNullOrBlank()) {
            errorCard.visibility = View.VISIBLE
            errorMessage.text = snap.errorMessage
            val canRetry = !snap.retryUrl.isNullOrBlank()
            openPageButton.visibility = if (canRetry) View.VISIBLE else View.GONE
            openPageHelp.visibility = if (canRetry) View.VISIBLE else View.GONE
        } else {
            errorCard.visibility = View.GONE
        }
    }

    /**
     * Mini-bar pinned to the bottom of Home while the reader isn't IDLE. While audio
     * hasn't started it names the step being waited on, with a progress bar and the
     * time it has taken so far.
     */
    private fun updateMiniBar(snap: ReaderSnapshot) {
        miniBar.removeCallbacks(stepTicker)
        if (readerContainer.visibility != View.VISIBLE || snap.status == ReaderStatus.IDLE) {
            miniBar.visibility = View.GONE
            return
        }
        miniBar.visibility = View.VISIBLE
        val label = snap.title.ifBlank { snap.host.ifBlank { getString(R.string.source_text) } }
        val step = app.readly.playback.LoadingStep.of(snap)
        app.readly.ui.LoadingViews.bindBar(miniProgress, step)
        if (step != null) {
            miniText.text = app.readly.ui.LoadingViews.title(this, step)
            app.readly.ui.LoadingViews.bindText(
                miniDetail,
                // Elapsed time first: a long title must not push it off the line.
                listOf(app.readly.ui.LoadingViews.detail(step, snap.stepSinceMs), label)
                    .filter { it.isNotBlank() }.joinToString(" · "),
            )
            miniBar.postDelayed(stepTicker, 1_000)
        } else {
            miniText.text = getString(R.string.minibar_format, label)
            miniDetail.visibility = View.GONE
        }
        miniToggle.setImageResource(
            if (snap.status == ReaderStatus.PAUSED) R.drawable.ic_play else R.drawable.ic_pause,
        )
    }

    private fun updateNotifRow() {
        val needAsk = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        notifRow.visibility = if (needAsk) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------
    // Voice install (first run)
    // ------------------------------------------------------------------

    private fun startInstall(target: VoiceModelStore) {
        val app = application as ReadlyApp
        if (app.installTask?.isDone == false) return
        lastSetupError = null
        installingPackId = target.pack.id
        val newTask = InstallTask(target) { finished ->
            if (app.installTask === finished) app.installTask = null
        }
        task = newTask
        newTask.listener = this
        app.installTask = newTask
        showProgress()
        updateProgress(InstallStage.DOWNLOADING, 0, target.pack.sizeBytes)
        app.ioExecutor.execute { newTask.run() }
    }

    private fun voicesDir(): File = File(noBackupFilesDir, "voices")

    /** A setup install target: the main voice or Pocket Farsi. */
    private fun setupTarget(packId: String): VoiceModelStore =
        if (packId == store.pack.id) {
            store
        } else {
            VoiceModelStore(VoiceCatalog.byId(packId)!!, voicesDir(), VoiceModelStore::httpUrlOpener)
        }

    private fun isSetupPack(packId: String): Boolean =
        packId == store.pack.id || packId == POCKET_PACK_ID

    override fun onProgress(stage: InstallStage, bytesDone: Long, bytesTotal: Long) {
        updateProgress(stage, bytesDone, bytesTotal)
    }

    override fun onDone() {
        Log.i("Readly", "Voice install complete")
        if (installingPackId == POCKET_PACK_ID) {
            // A Persian-only setup reads Persian right away, Smart voice or
            // not: the explicit fa pick covers MainVoice mode too.
            ReaderService.setPackIdForLang(this, "fa", POCKET_PACK_ID)
            Log.i("Readly", "Persian voice set to Tiny Ava after setup install")
        }
        installingPackId = null
        task = null
        lastSetupError = null
        showReader()
    }

    override fun onCancelled() {
        Log.i("Readly", "Voice install cancelled by user")
        task = null
        lastSetupError = null
        installingPackId = null
        showIdle()
    }

    override fun onError(e: Exception) {
        Log.e("Readly", "Voice download failed", e)
        task = null
        // installingPackId stays: retry reinstalls the same pack.
        lastSetupError = e
        errorText.text = getString(
            R.string.error_format,
            e.javaClass.name,
            e.message ?: "(no details)",
        )
        showError()
    }

    /**
     * Setup voice name from the family card title + variant (e.g. "Supertonic v2").
     * Piper packs use the short name ("Piper Lessac") so the voice is named.
     */
    private fun setupVoiceName(): String {
        val pack = store.pack
        return if (pack.family == VoiceFamily.PIPER) {
            VoiceCatalog.shortName(pack)
        } else {
            "${VoiceCatalog.familyInfo(pack.family).title} ${pack.variant}"
        }
    }

    /** Download estimate from the measured rate (320 MB took ~3 min on a Pixel). */
    private fun updateSetupEstimate(sizeMb: Int) {
        val minutes = maxOf(1, (sizeMb / 100.0).roundToInt())
        setupEstimate.text = if (minutes == 1) {
            getString(R.string.setup_eta_one)
        } else {
            getString(R.string.setup_eta_many, minutes)
        }
    }

    private fun toggleSetupSample() {
        if (setupPlaying) {
            setupSamples.stop()
            setupPlaying = false
            setupLoading = false
            repaintSetupSampleButton()
            return
        }
        val url = store.pack.sampleUrl
        val reason = if (url != null) {
            setupSamples.playUrl(
                url,
                onPrepared = {
                    setupLoading = false
                    repaintSetupSampleButton()
                },
                onDone = {
                    setupPlaying = false
                    setupLoading = false
                    repaintSetupSampleButton()
                },
                onAsyncError = { asyncReason -> setupSampleError(asyncReason) },
            )
        } else {
            setupSamples.play(
                this,
                store.pack.sampleRes,
                onDone = {
                    setupPlaying = false
                    repaintSetupSampleButton()
                },
                onAsyncError = { asyncReason -> setupSampleError(asyncReason) },
            )
        }
        if (reason != null) {
            setupSampleError(reason)
        } else {
            setupPlaying = true
            setupLoading = url != null
        }
        repaintSetupSampleButton()
    }

    private fun repaintSetupSampleButton() {
        setupSampleButton.setText(
            when {
                setupLoading -> R.string.sample_loading
                setupPlaying -> R.string.stop_button
                else -> R.string.play_sample_button
            },
        )
    }

    private fun setupSampleError(reason: String) {
        setupPlaying = false
        setupLoading = false
        val message = getString(R.string.sample_error_format, reason)
        Log.e("Readly", "$message (${store.pack.id})")
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        repaintSetupSampleButton()
    }

    private fun updateProgress(stage: InstallStage, bytesDone: Long, bytesTotal: Long) {
        val totalMb = installingPackId?.let { VoiceCatalog.byId(it)?.sizeMb } ?: store.pack.sizeMb
        progressLabel.text = when (stage) {
            InstallStage.DOWNLOADING ->
                getString(
                    R.string.downloading_format,
                    bytesDone / 1_000_000,
                    totalMb,
                )
            InstallStage.VERIFYING -> getString(R.string.verifying_label)
        }
        progressBar.progress =
            if (bytesTotal > 0) ((bytesDone * 100 / bytesTotal).toInt().coerceIn(0, 100)) else 0
    }

    private fun showIdle() {
        setupContainer.visibility = View.VISIBLE
        readerContainer.visibility = View.GONE
        homeRefresh.isEnabled = false
        refreshButton.isEnabled = false
        homeHeader.visibility = View.GONE
        homeTabs.visibility = View.GONE
        homeFab.visibility = View.GONE
        setSetupVisible(true)
        setProgressVisible(false)
        setErrorVisible(false)
        updateMiniBar(ReaderState.snapshot)
    }

    private fun showProgress() {
        setupContainer.visibility = View.VISIBLE
        readerContainer.visibility = View.GONE
        homeRefresh.isEnabled = false
        refreshButton.isEnabled = false
        homeHeader.visibility = View.GONE
        homeTabs.visibility = View.GONE
        homeFab.visibility = View.GONE
        setSetupVisible(true)
        downloadButton.visibility = View.GONE
        downloadHelp.visibility = View.GONE
        pocketButton.visibility = View.GONE
        pocketHelp.visibility = View.GONE
        setProgressVisible(true)
        setErrorVisible(false)
        updateMiniBar(ReaderState.snapshot)
    }

    private fun showError() {
        setupContainer.visibility = View.VISIBLE
        readerContainer.visibility = View.GONE
        homeRefresh.isEnabled = false
        refreshButton.isEnabled = false
        homeHeader.visibility = View.GONE
        homeTabs.visibility = View.GONE
        homeFab.visibility = View.GONE
        setSetupVisible(true)
        downloadButton.visibility = View.GONE
        downloadHelp.visibility = View.GONE
        pocketButton.visibility = View.GONE
        pocketHelp.visibility = View.GONE
        setProgressVisible(false)
        setErrorVisible(true)
        updateMiniBar(ReaderState.snapshot)
    }

    private fun showReader() {
        setupContainer.visibility = View.GONE
        readerContainer.visibility = View.VISIBLE
        homeRefresh.isEnabled = true
        refreshButton.isEnabled = !homeRefresh.isRefreshing
        homeHeader.visibility = View.VISIBLE
        homeTabs.visibility = View.VISIBLE
        homeFab.visibility = View.VISIBLE
        updateNotifRow()
        updateMiniBar(ReaderState.snapshot)
    }

    private fun setSetupVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        title.visibility = v
        body.visibility = v
        downloadButton.visibility = v
        downloadHelp.visibility = v
        pocketButton.visibility = v
        pocketHelp.visibility = v
    }

    private fun setProgressVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        progressBar.visibility = v
        progressLabel.visibility = v
        cancelButton.visibility = v
        cancelHelp.visibility = v
    }

    private fun setErrorVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        errorText.visibility = v
        retryButton.visibility = v
        retryHelp.visibility = v
    }

    companion object {
        private const val NOTIF_REQUEST = 1
        private const val FILE_REQUEST = 2

        /** Boolean extra: a share arrived before the voice was installed. */
        const val EXTRA_NEED_VOICE = "needVoice"

        /** The Persian-only setup alternative to the main voice. */
        const val POCKET_PACK_ID = "tiny-ava"
    }
}
