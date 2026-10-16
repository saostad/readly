package app.readly

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import app.readly.model.InstallStage
import app.readly.model.VoiceCatalog
import app.readly.model.VoiceModelStore
import app.readly.model.VoicePack
import app.readly.playback.ReaderService
import java.io.File
import java.io.IOException

/**
 * Lists every [VoicePack]: play its sample clip, download it, switch to it or delete
 * it. Downloads reuse the single app-wide [InstallTask], so only one install runs at
 * a time no matter which screen started it.
 */
class VoicesActivity : Activity(), InstallTask.Listener {
    private lateinit var rowsBox: LinearLayout
    private lateinit var prefs: SharedPreferences
    private val rows = mutableListOf<Row>()
    private val samples = SamplePlayer()
    private var playingPackId: String? = null
    private var task: InstallTask? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voices)
        rowsBox = findViewById(R.id.rowsContainer)
        prefs = getSharedPreferences(ReaderService.PREFS_NAME, MODE_PRIVATE)
        for (pack in VoiceCatalog.ALL) {
            val row = Row(pack)
            rows.add(row)
            rowsBox.addView(row.root)
        }
    }

    override fun onResume() {
        super.onResume()
        val running = (application as ReadlyApp).installTask
        if (running != null && !running.isDone) {
            task = running
            running.listener = this
        } else {
            task = null
        }
        refresh()
        task?.let { updateProgress(it.lastStage, it.lastDoneBytes, it.lastTotalBytes) }
    }

    override fun onPause() {
        task?.let { if (it.listener === this) it.listener = null }
        super.onPause()
    }

    override fun onStop() {
        samples.release()
        playingPackId = null
        super.onStop()
    }

    override fun onDestroy() {
        samples.release()
        task?.let { if (it.listener === this) it.listener = null }
        super.onDestroy()
    }

    private fun voicesDir(): File = File(noBackupFilesDir, "voices")

    private fun row(id: String): Row = rows.first { it.pack.id == id }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()

    // ------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------

    private inner class Row(val pack: VoicePack) {
        val root: LinearLayout = LinearLayout(this@VoicesActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            val v = dp(12)
            setPadding(0, v, 0, v)
        }
        val statusText = TextView(this@VoicesActivity)
        val noteText = TextView(this@VoicesActivity).apply {
            setText(R.string.another_downloading_note)
            visibility = View.GONE
        }
        val errorText = TextView(this@VoicesActivity).apply {
            setTextColor(getColor(android.R.color.holo_red_dark))
            visibility = View.GONE
        }
        val sampleButton = Button(this@VoicesActivity).apply {
            setText(R.string.play_sample_button)
            setOnClickListener { toggleSample(pack) }
        }
        val downloadButton = Button(this@VoicesActivity).apply {
            text = getString(R.string.download_pack_format, pack.sizeMb)
            setOnClickListener { startInstall(pack) }
        }
        val progressBar = ProgressBar(
            this@VoicesActivity, null, android.R.attr.progressBarStyleHorizontal,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            max = 100
            visibility = View.GONE
        }
        val progressLabel = TextView(this@VoicesActivity).apply {
            visibility = View.GONE
        }
        val cancelButton = Button(this@VoicesActivity).apply {
            setText(R.string.cancel_button)
            setOnClickListener { task?.cancel() }
            visibility = View.GONE
        }
        val useButton = Button(this@VoicesActivity).apply {
            setText(R.string.use_voice_button)
            setOnClickListener { usePack(pack) }
            visibility = View.GONE
        }
        val deleteButton = Button(this@VoicesActivity).apply {
            setText(R.string.delete_button)
            setOnClickListener { confirmDelete(pack) }
            visibility = View.GONE
        }
        val deleteHelp = TextView(this@VoicesActivity).apply {
            setText(R.string.in_use_delete_help)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            visibility = View.GONE
        }

        init {
            val nameText = TextView(this@VoicesActivity).apply {
                text = pack.displayName
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            }
            val infoText = TextView(this@VoicesActivity).apply {
                text = getString(R.string.pack_info_format, pack.sizeMb, pack.rtfNote)
            }
            root.addView(nameText)
            root.addView(infoText)
            root.addView(statusText)
            root.addView(sampleButton)
            root.addView(downloadButton)
            root.addView(noteText)
            root.addView(progressBar)
            root.addView(progressLabel)
            root.addView(cancelButton)
            root.addView(useButton)
            root.addView(deleteButton)
            root.addView(deleteHelp)
            root.addView(errorText)
            root.addView(
                View(this@VoicesActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1),
                    ).apply { topMargin = dp(12) }
                    setBackgroundColor(0xFF888888.toInt())
                },
            )
        }

        fun showError(message: String) {
            errorText.text = message
            errorText.visibility = View.VISIBLE
        }

        fun clearError() {
            errorText.visibility = View.GONE
        }
    }

    /** Re-reads installed/active/installing state from disk and repaints every row. */
    private fun refresh() {
        val activeId = ReaderService.activePackId(this)
        val running = (application as ReadlyApp).installTask
        val runningId = if (running != null && !running.isDone) running.packId else null
        for (r in rows) {
            val installed =
                VoiceModelStore(r.pack, voicesDir(), VoiceModelStore::httpUrlOpener).isInstalled()
            val inUse = installed && r.pack.id == activeId
            r.statusText.setText(
                when {
                    inUse -> R.string.status_in_use
                    installed -> R.string.status_downloaded
                    else -> R.string.status_not_downloaded
                },
            )
            val installingThis = runningId == r.pack.id
            val anotherRunning = runningId != null && !installingThis
            r.sampleButton.setText(
                if (playingPackId == r.pack.id) R.string.stop_button
                else R.string.play_sample_button,
            )
            r.downloadButton.visibility = if (!installed && !installingThis) View.VISIBLE else View.GONE
            r.downloadButton.isEnabled = !anotherRunning
            r.noteText.visibility =
                if (!installed && !installingThis && anotherRunning) View.VISIBLE else View.GONE
            r.progressBar.visibility = if (installingThis) View.VISIBLE else View.GONE
            r.progressLabel.visibility = if (installingThis) View.VISIBLE else View.GONE
            r.cancelButton.visibility = if (installingThis) View.VISIBLE else View.GONE
            r.useButton.visibility = if (installed && !inUse) View.VISIBLE else View.GONE
            r.deleteButton.visibility = if (installed && !inUse) View.VISIBLE else View.GONE
            r.deleteHelp.visibility = if (inUse) View.VISIBLE else View.GONE
        }
    }

    // ------------------------------------------------------------------
    // Sample clips
    // ------------------------------------------------------------------

    private fun toggleSample(pack: VoicePack) {
        if (playingPackId == pack.id) {
            samples.stop()
            playingPackId = null
            refresh()
            return
        }
        row(pack.id).clearError()
        val reason = samples.play(
            this,
            pack.sampleRes,
            onDone = {
                playingPackId = null
                refresh()
            },
            onAsyncError = { asyncReason ->
                playingPackId = null
                sampleError(pack, asyncReason)
            },
        )
        if (reason != null) {
            sampleError(pack, reason)
        } else {
            // play() stops any previous clip first, so this one is now the only one.
            playingPackId = pack.id
        }
        refresh()
    }

    private fun sampleError(pack: VoicePack, reason: String) {
        val message = getString(R.string.sample_error_format, reason)
        Log.e("Readly", "$message (${pack.id})")
        row(pack.id).showError(message)
        refresh()
    }

    // ------------------------------------------------------------------
    // Install (shares the single app-wide InstallTask with the setup screen)
    // ------------------------------------------------------------------

    private fun startInstall(pack: VoicePack) {
        val app = application as ReadlyApp
        if (app.installTask?.isDone == false) return
        row(pack.id).clearError()
        val store = VoiceModelStore(pack, voicesDir(), VoiceModelStore::httpUrlOpener)
        val newTask = InstallTask(store) { finished ->
            if (app.installTask === finished) app.installTask = null
        }
        task = newTask
        newTask.listener = this
        app.installTask = newTask
        refresh()
        updateProgress(InstallStage.DOWNLOADING, 0, pack.sizeBytes)
        app.ioExecutor.execute { newTask.run() }
    }

    override fun onProgress(stage: InstallStage, bytesDone: Long, bytesTotal: Long) {
        updateProgress(stage, bytesDone, bytesTotal)
    }

    private fun updateProgress(stage: InstallStage, bytesDone: Long, bytesTotal: Long) {
        val id = task?.packId ?: return
        val row = row(id)
        row.progressBar.progress =
            if (bytesTotal > 0) (bytesDone * 100 / bytesTotal).toInt().coerceIn(0, 100) else 0
        row.progressLabel.text = when (stage) {
            InstallStage.DOWNLOADING ->
                getString(R.string.downloading_format, bytesDone / 1_000_000, row.pack.sizeMb)
            InstallStage.VERIFYING -> getString(R.string.verifying_label)
        }
    }

    override fun onDone() {
        val id = task?.packId
        task = null
        if (id != null) {
            Log.i("Readly", "Voice install complete ($id)")
            row(id).clearError()
        }
        refresh()
        if (id != null && !isFinishing && !isDestroyed) {
            val pack = VoiceCatalog.byId(id) ?: return
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.use_now_format, pack.displayName))
                .setPositiveButton(android.R.string.yes) { _, _ -> usePack(pack) }
                .setNegativeButton(android.R.string.no, null)
                .show()
        }
    }

    override fun onCancelled() {
        Log.i("Readly", "Voice install cancelled by user")
        task = null
        refresh()
    }

    override fun onError(e: Exception) {
        val id = task?.packId
        task = null
        val message = getString(
            R.string.error_format,
            e.javaClass.name,
            e.message ?: "(no details)",
        )
        Log.e("Readly", "Voice download failed", e)
        if (id != null) row(id).showError(message)
        refresh()
    }

    // ------------------------------------------------------------------
    // Switch + delete
    // ------------------------------------------------------------------

    private fun usePack(pack: VoicePack) {
        prefs.edit().putString(ReaderService.KEY_ACTIVE_PACK, pack.id).apply()
        ReaderService.preload(this)
        Toast.makeText(
            this,
            getString(R.string.use_voice_toast_format, pack.displayName),
            Toast.LENGTH_LONG,
        ).show()
        refresh()
    }

    private fun confirmDelete(pack: VoicePack) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_pack_confirm_format, pack.displayName, pack.sizeMb))
            .setPositiveButton(R.string.delete_button) { _, _ -> deletePack(pack) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deletePack(pack: VoicePack) {
        val row = row(pack.id)
        row.clearError()
        // Safe on Linux: files a still-loaded engine has open or mmap'd stay valid
        // until it closes them. The preload on switch closes the old engine anyway,
        // and the in-use pack has no Delete button at all.
        try {
            VoiceModelStore(pack, voicesDir(), VoiceModelStore::httpUrlOpener).delete()
            if (File(voicesDir(), pack.topDir).exists()) {
                throw IOException("files could not be removed")
            }
        } catch (e: Exception) {
            val message = getString(
                R.string.delete_error_format,
                pack.displayName,
                e.javaClass.name,
                e.message ?: "(no details)",
            )
            Log.e("Readly", message, e)
            row.showError(message)
        }
        refresh()
    }
}
