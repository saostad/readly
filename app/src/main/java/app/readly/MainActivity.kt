package app.readly

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import app.readly.model.InstallStage
import app.readly.model.VoiceModelStore
import java.io.File

/**
 * First-run setup screen: downloads the Kokoro voice, then shows a placeholder until round 2
 * adds the reader.
 */
class MainActivity : Activity(), InstallTask.Listener {

    private lateinit var store: VoiceModelStore
    private var task: InstallTask? = null

    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var downloadButton: Button
    private lateinit var downloadHelp: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressLabel: TextView
    private lateinit var cancelButton: Button
    private lateinit var cancelHelp: TextView
    private lateinit var errorText: TextView
    private lateinit var retryButton: Button
    private lateinit var retryHelp: TextView
    private lateinit var readyTitle: TextView
    private lateinit var readyHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        title = findViewById(R.id.title)
        body = findViewById(R.id.body)
        downloadButton = findViewById(R.id.downloadButton)
        downloadHelp = findViewById(R.id.downloadHelp)
        progressBar = findViewById(R.id.progressBar)
        progressLabel = findViewById(R.id.progressLabel)
        cancelButton = findViewById(R.id.cancelButton)
        cancelHelp = findViewById(R.id.cancelHelp)
        errorText = findViewById(R.id.errorText)
        retryButton = findViewById(R.id.retryButton)
        retryHelp = findViewById(R.id.retryHelp)
        readyTitle = findViewById(R.id.readyTitle)
        readyHint = findViewById(R.id.readyHint)

        store = VoiceModelStore(File(noBackupFilesDir, "voices"), VoiceModelStore::httpUrlOpener)
        downloadButton.setOnClickListener { startInstall() }
        retryButton.setOnClickListener { startInstall() }
        cancelButton.setOnClickListener { task?.cancel() }

        if (store.isInstalled()) {
            showReady()
        } else {
            // Re-attach to the running install after rotation instead of restarting it.
            val running = (application as ReadlyApp).installTask
            if (running != null && !running.isDone) {
                task = running
                running.listener = this
                showProgress()
                updateProgress(running.lastStage, running.lastDoneBytes, running.lastTotalBytes)
            } else {
                showIdle()
            }
        }
    }

    override fun onDestroy() {
        task?.let { if (it.listener === this) it.listener = null }
        super.onDestroy()
    }

    private fun startInstall() {
        val app = application as ReadlyApp
        if (app.installTask?.isDone == false) return
        val newTask = InstallTask(store) { finished ->
            if (app.installTask === finished) app.installTask = null
        }
        task = newTask
        newTask.listener = this
        app.installTask = newTask
        showProgress()
        updateProgress(InstallStage.DOWNLOADING, 0, VoiceModelStore.ARCHIVE_SIZE_BYTES)
        app.ioExecutor.execute { newTask.run() }
    }

    override fun onProgress(stage: InstallStage, bytesDone: Long, bytesTotal: Long) {
        updateProgress(stage, bytesDone, bytesTotal)
    }

    override fun onDone() {
        Log.i("Readly", "Voice install complete")
        task = null
        showReady()
    }

    override fun onCancelled() {
        Log.i("Readly", "Voice install cancelled by user")
        task = null
        showIdle()
    }

    override fun onError(e: Exception) {
        Log.e("Readly", "Voice download failed", e)
        task = null
        errorText.text = getString(
            R.string.error_format,
            e.javaClass.name,
            e.message ?: "(no details)",
        )
        showError()
    }

    private fun updateProgress(stage: InstallStage, bytesDone: Long, bytesTotal: Long) {
        progressLabel.text = when (stage) {
            InstallStage.DOWNLOADING ->
                getString(R.string.downloading_format, bytesDone / 1024 / 1024)
            InstallStage.VERIFYING -> getString(R.string.verifying_label)
            InstallStage.UNPACKING -> getString(R.string.unpacking_label)
        }
        progressBar.progress =
            if (bytesTotal > 0) ((bytesDone * 100 / bytesTotal).toInt().coerceIn(0, 100)) else 0
    }

    private fun showIdle() {
        setSetupVisible(true)
        setProgressVisible(false)
        setErrorVisible(false)
        setReadyVisible(false)
    }

    private fun showProgress() {
        setSetupVisible(true)
        downloadButton.visibility = View.GONE
        downloadHelp.visibility = View.GONE
        setProgressVisible(true)
        setErrorVisible(false)
        setReadyVisible(false)
    }

    private fun showError() {
        setSetupVisible(true)
        downloadButton.visibility = View.GONE
        downloadHelp.visibility = View.GONE
        setProgressVisible(false)
        setErrorVisible(true)
        setReadyVisible(false)
    }

    private fun showReady() {
        setSetupVisible(false)
        setProgressVisible(false)
        setErrorVisible(false)
        setReadyVisible(true)
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

    private fun setReadyVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        readyTitle.visibility = v
        readyHint.visibility = v
    }
}
