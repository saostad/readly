package app.readly

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
import app.readly.ui.SettingsSheet
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * First-run voice download, then Home: a card to read text aloud, voice chips,
 * a phone-voice tip and a footer hint. Playback state arrives from [ReaderState],
 * so rotation never loses it; voice and speed live in the settings sheet.
 */
class MainActivity : Activity(), InstallTask.Listener {

    private lateinit var store: VoiceModelStore
    private lateinit var prefs: SharedPreferences
    private var task: InstallTask? = null
    private var lastSetupError: Exception? = null
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
    private lateinit var chipRow: LinearLayout
    private lateinit var voiceChip: Button
    private lateinit var speedChip: Button
    private lateinit var autoChip: Button
    private lateinit var tipCard: LinearLayout
    private lateinit var tipSetup: Button
    private lateinit var needVoiceBanner: TextView

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
        chipRow = findViewById(R.id.chipRow)
        voiceChip = findViewById(R.id.voiceChip)
        speedChip = findViewById(R.id.speedChip)
        autoChip = findViewById(R.id.autoChip)
        tipCard = findViewById(R.id.tipCard)
        tipSetup = findViewById(R.id.tipSetup)
        needVoiceBanner = findViewById(R.id.needVoiceBanner)
        if (intent?.getBooleanExtra(EXTRA_NEED_VOICE, false) == true) {
            needVoiceBanner.visibility = View.VISIBLE
        }

        val pack = ReaderService.activePack(this)
        store = VoiceModelStore(pack, File(noBackupFilesDir, "voices"), VoiceModelStore::httpUrlOpener)
        prefs = getSharedPreferences(ReaderService.PREFS_NAME, MODE_PRIVATE)
        body.text = getString(R.string.setup_body, setupVoiceName(), pack.sizeMb)
        downloadButton.text = getString(R.string.download_button, pack.sizeMb)
        updateSetupEstimate(pack.sizeMb)

        setupSampleButton.setOnClickListener { toggleSetupSample() }
        chooseVoiceButton.setOnClickListener {
            startActivity(Intent(this, VoicesActivity::class.java))
        }
        downloadButton.setOnClickListener { startInstall() }
        retryButton.setOnClickListener { startInstall() }
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
        tipSetup.setOnClickListener {
            PhoneVoiceSettings.open(
                this,
                getString(R.string.phone_voice_toast),
                getString(R.string.phone_voice_fallback_toast),
            )
        }
        refreshChips()

        if (store.isInstalled()) {
            showReader()
        } else {
            // Re-attach to the running install after rotation instead of restarting it
            // (only when it is ours; another pack's install belongs to its own screen).
            val running = (application as ReadlyApp).installTask
            if (running != null && !running.isDone && running.packId == store.pack.id) {
                task = running
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
    }

    override fun onPause() {
        task?.let { if (it.listener === this) it.listener = null }
        super.onPause()
    }

    override fun onStop() {
        ReaderState.removeListener(readerListener)
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
     * views live in the reader container, so setup never shows them.
     */
    private fun updateTip() {
        tipCard.visibility =
            if (PhoneVoiceSettings.status(this).isOurs) View.GONE else View.VISIBLE
    }

    /**
     * Shows reader, progress, error or idle from the current disk state: an install
     * that finished on the Voices screen while we were away lands here, as does a
     * re-attach to our still-running install.
     */
    private fun syncSetupState() {
        if (store.isInstalled()) {
            showReader()
            return
        }
        val running = (application as ReadlyApp).installTask
        if (running != null && !running.isDone && running.packId == store.pack.id) {
            task = running
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
        val title = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(60) ?: ""
        ReaderService.read(this, text, title, SourceFormat.PLAIN)
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
        autoChip.setCompoundDrawablesWithIntrinsicBounds(
            if (smartOn) R.drawable.ic_check else 0,
            0,
            0,
            0,
        )
    }

    /** The reading notices render as one dismissible card above the voice chips. */
    private fun render(snap: ReaderSnapshot) {
        if (!snap.notice.isNullOrBlank()) {
            noticeCard.visibility = View.VISIBLE
            noticeText.text = snap.notice
        } else {
            noticeCard.visibility = View.GONE
        }
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

    private fun startInstall() {
        val app = application as ReadlyApp
        if (app.installTask?.isDone == false) return
        lastSetupError = null
        val newTask = InstallTask(store) { finished ->
            if (app.installTask === finished) app.installTask = null
        }
        task = newTask
        newTask.listener = this
        app.installTask = newTask
        showProgress()
        updateProgress(InstallStage.DOWNLOADING, 0, store.pack.sizeBytes)
        app.ioExecutor.execute { newTask.run() }
    }

    override fun onProgress(stage: InstallStage, bytesDone: Long, bytesTotal: Long) {
        updateProgress(stage, bytesDone, bytesTotal)
    }

    override fun onDone() {
        Log.i("Readly", "Voice install complete")
        task = null
        lastSetupError = null
        showReader()
    }

    override fun onCancelled() {
        Log.i("Readly", "Voice install cancelled by user")
        task = null
        lastSetupError = null
        showIdle()
    }

    override fun onError(e: Exception) {
        Log.e("Readly", "Voice download failed", e)
        task = null
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
        progressLabel.text = when (stage) {
            InstallStage.DOWNLOADING ->
                getString(
                    R.string.downloading_format,
                    bytesDone / 1_000_000,
                    store.pack.sizeMb,
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
    }

    private fun showProgress() {
        setupContainer.visibility = View.VISIBLE
        readerContainer.visibility = View.GONE
        setSetupVisible(true)
        downloadButton.visibility = View.GONE
        downloadHelp.visibility = View.GONE
        setProgressVisible(true)
        setErrorVisible(false)
    }

    private fun showError() {
        setupContainer.visibility = View.VISIBLE
        readerContainer.visibility = View.GONE
        setSetupVisible(true)
        downloadButton.visibility = View.GONE
        downloadHelp.visibility = View.GONE
        setProgressVisible(false)
        setErrorVisible(true)
    }

    private fun showReader() {
        setupContainer.visibility = View.GONE
        readerContainer.visibility = View.VISIBLE
        updateNotifRow()
    }

    private fun setSetupVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        title.visibility = v
        body.visibility = v
        downloadButton.visibility = v
        downloadHelp.visibility = v
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
    }
}
