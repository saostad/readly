package app.readly

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import app.readly.model.InstallStage
import app.readly.model.VoiceModelStore
import app.readly.playback.ReaderService
import app.readly.playback.ReaderSnapshot
import app.readly.playback.ReaderState
import app.readly.playback.ReaderStatus
import app.readly.text.SourceFormat
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * First-run voice download, then the reader: a text box to read aloud, playback
 * controls, voice and speed settings. Playback state arrives from [ReaderState], so
 * rotation never loses it.
 */
class MainActivity : Activity(), InstallTask.Listener {

    private lateinit var store: VoiceModelStore
    private lateinit var prefs: SharedPreferences
    private var task: InstallTask? = null
    private var lastSetupError: Exception? = null
    private val setupSamples = SamplePlayer()
    private var setupPlaying = false

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
    private lateinit var inputEdit: EditText
    private lateinit var readButton: Button
    private lateinit var inputError: TextView
    private lateinit var nowContainer: LinearLayout
    private lateinit var nowTitle: TextView
    private lateinit var nowSentence: TextView
    private lateinit var nowProgress: TextView
    private lateinit var statusLine: TextView
    private lateinit var speedLine: TextView
    private lateinit var noticeText: TextView
    private lateinit var needVoiceBanner: TextView
    private lateinit var prevButton: Button
    private lateinit var pausePlayButton: Button
    private lateinit var nextButton: Button
    private lateinit var stopButton: Button
    private lateinit var voicePackLabel: TextView
    private lateinit var changePackButton: Button
    private lateinit var voiceSpinner: Spinner
    private lateinit var speedLabel: TextView
    private lateinit var speedSeek: SeekBar

    private var defaultTextColor = 0

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
        inputEdit = findViewById(R.id.inputEdit)
        readButton = findViewById(R.id.readButton)
        inputError = findViewById(R.id.inputError)
        nowContainer = findViewById(R.id.nowContainer)
        nowTitle = findViewById(R.id.nowTitle)
        nowSentence = findViewById(R.id.nowSentence)
        nowProgress = findViewById(R.id.nowProgress)
        statusLine = findViewById(R.id.statusLine)
        speedLine = findViewById(R.id.speedLine)
        noticeText = findViewById(R.id.noticeText)
        needVoiceBanner = findViewById(R.id.needVoiceBanner)
        if (intent?.getBooleanExtra(EXTRA_NEED_VOICE, false) == true) {
            needVoiceBanner.visibility = View.VISIBLE
        }
        prevButton = findViewById(R.id.prevButton)
        pausePlayButton = findViewById(R.id.pausePlayButton)
        nextButton = findViewById(R.id.nextButton)
        stopButton = findViewById(R.id.stopButton)
        voicePackLabel = findViewById(R.id.voicePackLabel)
        changePackButton = findViewById(R.id.changePackButton)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        speedLabel = findViewById(R.id.speedLabel)
        speedSeek = findViewById(R.id.speedSeek)
        defaultTextColor = statusLine.currentTextColor

        val pack = ReaderService.activePack(this)
        store = VoiceModelStore(pack, File(noBackupFilesDir, "voices"), VoiceModelStore::httpUrlOpener)
        prefs = getSharedPreferences(ReaderService.PREFS_NAME, MODE_PRIVATE)
        body.text = getString(R.string.setup_body, pack.displayName, pack.sizeMb)
        downloadButton.text = getString(R.string.download_button, pack.sizeMb)
        voicePackLabel.text = getString(R.string.voice_pack_format, pack.displayName)
        updateSetupEstimate(pack.sizeMb)

        setupSampleButton.setOnClickListener { toggleSetupSample() }
        chooseVoiceButton.setOnClickListener {
            startActivity(Intent(this, VoicesActivity::class.java))
        }
        downloadButton.setOnClickListener { startInstall() }
        retryButton.setOnClickListener { startInstall() }
        cancelButton.setOnClickListener { task?.cancel() }

        readButton.setOnClickListener { readInputAloud() }
        prevButton.setOnClickListener { sendAction(ReaderService.ACTION_PREVIOUS) }
        pausePlayButton.setOnClickListener { sendAction(ReaderService.ACTION_TOGGLE) }
        nextButton.setOnClickListener { sendAction(ReaderService.ACTION_NEXT) }
        stopButton.setOnClickListener { sendAction(ReaderService.ACTION_STOP) }
        allowButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIF_REQUEST,
                )
            }
        }
        changePackButton.setOnClickListener {
            startActivity(Intent(this, VoicesActivity::class.java))
        }
        setupVoiceSpinner()
        setupSpeedSeek()

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
            body.text = getString(R.string.setup_body, pack.displayName, pack.sizeMb)
            downloadButton.text = getString(R.string.download_button, pack.sizeMb)
            updateSetupEstimate(pack.sizeMb)
            refreshVoiceSection()
        }
        repaintSetupSampleButton()
        syncSetupState()
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
    // Reader
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

    private fun sendAction(action: String) {
        startService(Intent(this, ReaderService::class.java).setAction(action))
    }

    private fun refreshVoiceSection() {
        voicePackLabel.text = getString(R.string.voice_pack_format, store.pack.displayName)
        setupVoiceSpinner()
    }

    private fun setupVoiceSpinner() {
        val pack = store.pack
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, pack.speakers)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        voiceSpinner.adapter = adapter
        voiceSpinner.setSelection(
            ReaderService.speakerIdFor(this, pack.id).coerceIn(pack.speakers.indices),
        )
        voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                position: Int,
                id: Long,
            ) {
                ReaderService.setSpeakerId(this@MainActivity, pack.id, position)
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
    }

    private fun setupSpeedSeek() {
        val speed = prefs.getFloat(ReaderService.KEY_SPEED, 1.0f)
        speedSeek.progress = speedToProgress(speed)
        updateSpeedLabel(speed)
        speedSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val speed = progressToSpeed(progress)
                updateSpeedLabel(speed)
                if (fromUser) prefs.edit().putFloat(ReaderService.KEY_SPEED, speed).apply()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun updateSpeedLabel(speed: Float) {
        speedLabel.text = getString(
            R.string.speed_label_format,
            String.format(Locale.US, "%.1f", speed),
        )
    }

    /** SeekBar 0..15 maps to 0.5×..2.0× in 0.1 steps. */
    private fun progressToSpeed(progress: Int): Float = 0.5f + progress * 0.1f

    private fun speedToProgress(speed: Float): Int =
        ((speed - 0.5f) * 10 + 0.5f).toInt().coerceIn(0, 15)

    private fun render(snap: ReaderSnapshot) {
        nowContainer.visibility =
            if (snap.status == ReaderStatus.IDLE) View.GONE else View.VISIBLE
        nowTitle.text = snap.title.ifBlank { snap.currentText.take(60) }
        nowTitle.visibility = if (nowTitle.text.isBlank()) View.GONE else View.VISIBLE
        nowSentence.text = snap.currentText
        nowSentence.visibility = if (snap.currentText.isBlank()) View.GONE else View.VISIBLE
        if (snap.total > 0) {
            nowProgress.visibility = View.VISIBLE
            nowProgress.text = getString(R.string.sentence_format, snap.index + 1, snap.total)
        } else {
            nowProgress.visibility = View.GONE
        }
        when (snap.status) {
            ReaderStatus.LOADING_VOICE -> {
                statusLine.visibility = View.VISIBLE
                statusLine.setText(R.string.loading_voice)
                statusLine.setTextColor(defaultTextColor)
            }
            ReaderStatus.FETCHING -> {
                statusLine.visibility = View.VISIBLE
                statusLine.text = snap.currentText.ifBlank { "Opening…" }
                statusLine.setTextColor(defaultTextColor)
            }
            ReaderStatus.ERROR -> {
                statusLine.visibility = View.VISIBLE
                statusLine.text = snap.errorMessage
                statusLine.setTextColor(getColor(android.R.color.holo_red_dark))
            }
            ReaderStatus.FINISHED -> {
                statusLine.visibility = View.VISIBLE
                statusLine.setText(R.string.finished_label)
                statusLine.setTextColor(defaultTextColor)
            }
            else -> statusLine.visibility = View.GONE
        }
        if (snap.lastRtf > 0) {
            speedLine.visibility = View.VISIBLE
            val factor = String.format(Locale.US, "%.1f", 1.0 / snap.lastRtf)
            if (snap.lastRtf > 1) {
                speedLine.text = getString(R.string.speed_slow_format, factor)
                speedLine.setTextColor(getColor(android.R.color.holo_orange_dark))
            } else {
                speedLine.text = getString(R.string.speed_fast_format, factor)
                speedLine.setTextColor(defaultTextColor)
            }
        } else {
            speedLine.visibility = View.GONE
        }
        if (!snap.notice.isNullOrBlank()) {
            noticeText.visibility = View.VISIBLE
            noticeText.text = snap.notice
        } else {
            noticeText.visibility = View.GONE
        }
        pausePlayButton.setText(
            if (snap.status == ReaderStatus.PAUSED) R.string.play_button else R.string.pause_button,
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
            repaintSetupSampleButton()
            return
        }
        val reason = setupSamples.play(
            this,
            store.pack.sampleRes,
            onDone = {
                setupPlaying = false
                repaintSetupSampleButton()
            },
            onAsyncError = { asyncReason -> setupSampleError(asyncReason) },
        )
        if (reason != null) {
            setupSampleError(reason)
        } else {
            setupPlaying = true
        }
        repaintSetupSampleButton()
    }

    private fun repaintSetupSampleButton() {
        setupSampleButton.setText(
            if (setupPlaying) R.string.stop_button else R.string.play_sample_button,
        )
    }

    private fun setupSampleError(reason: String) {
        setupPlaying = false
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
