package app.readly

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
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
import app.readly.ui.HomeBrowser
import app.readly.ui.SettingsSheet
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * First-run voice download, then Home: the Android Auto layout (Websites, Latest,
 * Saved and Recents tabs, see [HomeBrowser]); the top bar's text button opens a card
 * to read pasted text aloud, with the voice chips and the phone-voice tip. Playback state arrives from [ReaderState],
 * so rotation never loses it; voice and speed live in the settings sheet.
 * Setup shows only when nothing is installed, and offers Persian-only readers
 * Pocket Farsi as an alternative to the main voice.
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
    private lateinit var settingsButton: ImageButton
    private lateinit var inputEdit: EditText
    private lateinit var pasteButton: Button
    private lateinit var readButton: Button
    private lateinit var inputError: TextView
    private lateinit var noticeCard: LinearLayout
    private lateinit var noticeText: TextView
    private lateinit var dismissNotice: Button
    private lateinit var errorCard: LinearLayout
    private lateinit var errorMessage: TextView
    private lateinit var openPageButton: Button
    private lateinit var openPageHelp: TextView
    private lateinit var chipRow: LinearLayout
    private lateinit var voiceChip: Button
    private lateinit var speedChip: Button
    private lateinit var autoChip: Button
    private lateinit var tipSection: TextView
    private lateinit var tipCard: LinearLayout
    private lateinit var tipSetup: Button
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
    private lateinit var pastePanel: LinearLayout
    private lateinit var pasteToggle: ImageButton
    private lateinit var home: HomeBrowser

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
        settingsButton = findViewById(R.id.settingsButton)
        inputEdit = findViewById(R.id.inputEdit)
        pasteButton = findViewById(R.id.pasteButton)
        readButton = findViewById(R.id.readButton)
        inputError = findViewById(R.id.inputError)
        noticeCard = findViewById(R.id.noticeCard)
        noticeText = findViewById(R.id.noticeText)
        dismissNotice = findViewById(R.id.dismissNotice)
        errorCard = findViewById(R.id.errorCard)
        errorMessage = findViewById(R.id.errorMessage)
        openPageButton = findViewById(R.id.openPageButton)
        openPageHelp = findViewById(R.id.openPageHelp)
        chipRow = findViewById(R.id.chipRow)
        voiceChip = findViewById(R.id.voiceChip)
        speedChip = findViewById(R.id.speedChip)
        autoChip = findViewById(R.id.autoChip)
        tipSection = findViewById(R.id.tipSection)
        tipCard = findViewById(R.id.tipCard)
        tipSetup = findViewById(R.id.tipSetup)
        miniBar = findViewById(R.id.miniBar)
        miniText = findViewById(R.id.miniText)
        miniToggle = findViewById(R.id.miniToggle)
        miniDetail = findViewById(R.id.miniDetail)
        miniProgress = findViewById(R.id.miniBarProgress)
        needVoiceBanner = findViewById(R.id.needVoiceBanner)
        pastePanel = findViewById(R.id.pastePanel)
        pasteToggle = findViewById(R.id.pasteToggle)
        pasteToggle.setOnClickListener { setPastePanelOpen(pastePanel.visibility != View.VISIBLE) }
        home = HomeBrowser(
            this,
            tabRow = findViewById(R.id.tabRow),
            header = findViewById(R.id.browseHeader),
            back = findViewById(R.id.browseBack),
            headerTitle = findViewById(R.id.browseTitle),
            content = findViewById(R.id.browseContent),
        ) {
            startActivity(Intent(this, FeedsActivity::class.java))
            overridePendingTransition(R.anim.screen_slide_in, R.anim.screen_slide_out)
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

        readButton.setOnClickListener { readInputAloud() }
        pasteButton.setOnClickListener { pasteFromClipboard() }
        allowButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIF_REQUEST,
                )
            }
        }
        settingsButton.setOnClickListener { openSheet() }
        voiceChip.setOnClickListener { openSheet() }
        speedChip.setOnClickListener { openSheet() }
        autoChip.setOnClickListener { toggleSmartVoice() }
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
        tipSetup.setOnClickListener {
            PhoneVoiceSettings.open(
                this,
                getString(R.string.phone_voice_toast),
                getString(R.string.phone_voice_fallback_toast),
            )
        }
        miniBar.setOnClickListener {
            startActivity(Intent(this, NowPlayingActivity::class.java))
            overridePendingTransition(R.anim.screen_slide_in, R.anim.screen_slide_out)
        }
        miniToggle.setOnClickListener { sendAction(ReaderService.ACTION_TOGGLE) }
        refreshChips()

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
        when {
            pastePanel.visibility == View.VISIBLE -> setPastePanelOpen(false)
            home.onBack() -> Unit
            else -> super.onBackPressed()
        }
    }

    /** Shows or hides the paste-and-read card (and its voice chips and tip). */
    private fun setPastePanelOpen(open: Boolean) {
        pastePanel.visibility = if (open) View.VISIBLE else View.GONE
        pasteToggle.imageTintList = getColorStateList(if (open) R.color.accent else R.color.text2)
        if (open) inputEdit.requestFocus()
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
        refreshChips()
        updateTip()
        syncSetupState()
        // Warm the engine while the user pastes so the first READ starts instantly.
        if (store.isInstalled() && ReaderState.snapshot.status == ReaderStatus.IDLE) {
            ReaderService.preload(this)
        }
        updateNotifRow()
    }

    /**
     * Shows the phone-voice tip card unless Readly is already the default. The
     * section label hides with the card, so it never stands alone. The views
     * live in the reader container, so setup never shows them.
     */
    private fun updateTip() {
        val visibility =
            if (PhoneVoiceSettings.status(this).isOurs) View.GONE else View.VISIBLE
        tipSection.visibility = visibility
        tipCard.visibility = visibility
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

    private fun readInputAloud() {
        val text = inputEdit.text.toString()
        if (text.isBlank()) {
            inputError.visibility = View.VISIBLE
            return
        }
        inputError.visibility = View.GONE
        // Pasted links open in the automatic article engine (same URL/headline
        // classification as shares); real prose is read directly. No transient
        // Now Playing over the article browser while it loads.
        when (val route = app.readly.article.ArticleRoute.routeInput(text)) {
            is app.readly.article.ArticleRoute.Decision.Article -> {
                startActivity(ArticleActivity.intent(this, route.url))
                overridePendingTransition(R.anim.screen_slide_in, R.anim.screen_slide_out)
            }
            is app.readly.article.ArticleRoute.Decision.Prose -> {
                ReaderService.read(this, route.text, route.title, SourceFormat.PLAIN)
                startActivity(Intent(this, NowPlayingActivity::class.java))
                overridePendingTransition(R.anim.screen_slide_in, R.anim.screen_slide_out)
            }
            null -> {
                inputError.visibility = View.VISIBLE
            }
        }
    }

    private fun sendAction(action: String) {
        startService(Intent(this, ReaderService::class.java).setAction(action))
    }

    private fun pasteFromClipboard() {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = manager.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            .orEmpty()
        if (clip.isBlank()) {
            Toast.makeText(this, R.string.nothing_to_paste, Toast.LENGTH_SHORT).show()
            return
        }
        val start = min(inputEdit.selectionStart, inputEdit.selectionEnd).coerceAtLeast(0)
        val end = max(inputEdit.selectionStart, inputEdit.selectionEnd).coerceAtLeast(0)
        inputEdit.text.replace(start, end, clip)
    }

    private fun openSheet() {
        SettingsSheet(this) {
            refreshChips()
            updateTip()
        }.show()
    }

    private fun toggleSmartVoice() {
        val on = !prefs.getBoolean(ReaderService.KEY_SMART_VOICE, true)
        prefs.edit().putBoolean(ReaderService.KEY_SMART_VOICE, on).apply()
        Toast.makeText(
            this,
            if (on) R.string.smart_voice_on else R.string.smart_voice_off,
            Toast.LENGTH_SHORT,
        ).show()
        refreshChips()
    }

    /** Repaints the voice/speed/Auto chips from the current settings. */
    private fun refreshChips() {
        val pack = ReaderService.activePack(this)
        val family = VoiceCatalog.familyInfo(pack.family).title
        val sid = ReaderService.speakerIdFor(this, pack.id).coerceIn(pack.speakers.indices)
        val speaker = if (pack.speakers.size == 1) {
            pack.voiceName?.substringBefore(" (") ?: ReaderService.speakerShortName(pack, sid)
        } else {
            ReaderService.speakerShortName(pack, sid)
        }
        voiceChip.text = "$family · $speaker"
        // The chips must fit on one line; the speaker part goes first when narrow
        // (the style keeps the chip itself to one line either way).
        chipRow.post {
            val layout = voiceChip.layout
            if (layout != null && layout.lineCount > 0 && layout.getEllipsisCount(0) > 0) {
                voiceChip.text = family
            }
        }
        val speed = prefs.getFloat(ReaderService.KEY_SPEED, 1.0f)
        speedChip.text = getString(
            R.string.speed_value_format,
            String.format(Locale.US, "%.1f", speed),
        )
        val smartOn = prefs.getBoolean(ReaderService.KEY_SMART_VOICE, true)
        if (smartOn) {
            // Drawable and tint are both set here: the XML tint alone left the
            // icon invisible on the device (round 21).
            autoChip.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_check, 0, 0, 0)
            autoChip.compoundDrawableTintList = ColorStateList.valueOf(getColor(R.color.accent))
            autoChip.setTextColor(getColorStateList(R.color.chip_text))
        } else {
            autoChip.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            autoChip.setTextColor(getColor(R.color.text3))
        }
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
            Log.i("Readly", "Persian voice set to Pocket Farsi after setup install")
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
        setSetupVisible(true)
        setProgressVisible(false)
        setErrorVisible(false)
        updateMiniBar(ReaderState.snapshot)
    }

    private fun showProgress() {
        setupContainer.visibility = View.VISIBLE
        readerContainer.visibility = View.GONE
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

        /** Boolean extra: a share arrived before the voice was installed. */
        const val EXTRA_NEED_VOICE = "needVoice"

        /** The Persian-only setup alternative to the main voice. */
        const val POCKET_PACK_ID = "pocket-farsi-v2"
    }
}
