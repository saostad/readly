package app.readly

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import app.readly.input.pdf.PdfOcrPages
import app.readly.input.pdf.SharedImagePages
import app.readly.input.pdf.PdfPages
import app.readly.input.pdf.PdfImportStore
import app.readly.input.pdf.PdfFiles
import app.readly.input.pdf.PdfImportException
import app.readly.input.pdf.PdfText
import app.readly.playback.ReaderService
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps the URI grant alive while a shared document/image is imported off the UI thread. */
class DocumentImportActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private var stage = "opening"
    private val image: Boolean get() = intent.getBooleanExtra("sharedImage", false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
        }
        box.addView(TextView(this, null, 0, R.style.Title).apply { setText(if (image) R.string.image_import_title else R.string.pdf_import_title) })
        status = TextView(this, null, 0, R.style.Body).apply { setText(if (image) R.string.image_copying else R.string.pdf_copying) }
        box.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        box.addView(progress)
        box.addView(Button(this).apply {
            setText(android.R.string.cancel)
            setOnClickListener {
                cancelled.set(true)
                Toast.makeText(this@DocumentImportActivity, R.string.pdf_cancelled, Toast.LENGTH_SHORT).show()
                finish()
            }
        })
        setContentView(box)
        executor.execute(::importDocument)
    }

    private fun importDocument() {
        var source: File? = null
        var text: File? = null
        var handedOff = false
        var storage: PdfImportStore? = null
        var importId: String? = null
        var copied = false
        try {
            val uri = intent.data ?: throw PdfImportException("No file was shared. Share the picture or document again.")
            stage = "settings"
            val limits = PdfFiles.limits(this)
            val ocr = PdfFiles.ocrOptions(this)
            val title = intent.getStringExtra(Intent.EXTRA_TITLE)?.substringBeforeLast('.')?.ifBlank { null }
                ?: getString(if (image) R.string.image_default_title else R.string.pdf_default_title)
            text = PdfFiles.newText(this)
            val id = text.name.removeSuffix(".txt")
            importId = id
            val store = PdfImportStore(this)
            storage = store
            store.create(id, title, if (image) "image" else "pdf")
            source = store.sourceFile(id, if (image) "image" else "pdf")
            stage = "copying"
            contentResolver.openInputStream(uri)?.use { input ->
                PdfFiles.copy(input, source, limits) { cancelled.get() || Thread.currentThread().isInterrupted }
            } ?: throw PdfImportException("Couldn't open the shared file. Share it again from the original app.")
            copied = true
            stage = "extracting"
            store.status(id, "extracting")
            val summary = text.bufferedWriter().use { output ->
                val isCancelled = { cancelled.get() || Thread.currentThread().isInterrupted }
                val pages: PdfPages = if (image) {
                    SharedImagePages(this, source, ocr, isCancelled,
                        { store.status(id, "recognizing")
                            runOnUiThread { if (!isFinishing) status.setText(R.string.image_recognizing) } },
                        { draft -> store.savePage(id, draft, limits) })
                } else {
                    PdfOcrPages(this, source, limits, ocr, isCancelled,
                        { page, count -> store.status(id, "recognizing")
                            runOnUiThread { if (!isFinishing) status.text = getString(R.string.pdf_recognizing, page, count) } },
                        { draft -> store.savePage(id, draft, limits) })
                }
                pages.use { document ->
                    PdfText.extract(document, limits, output, { cancelled.get() || Thread.currentThread().isInterrupted }) { page, count ->
                        store.status(id, "extracting")
                        runOnUiThread {
                            if (!isFinishing) {
                                status.text = getString(R.string.pdf_extracting, page, count)
                                progress.isIndeterminate = false
                                progress.max = count
                                progress.progress = page
                            }
                        }
                    }
                }
            }
            if (cancelled.get()) throw CancellationException("Document import cancelled")
            if (summary.emptyPages > 0) {
                Log.w("Readly", "fallback=true attempted=pdf_page_text reason=no_extractable_text taken=skip_empty_pages pages=${summary.pages} emptyPages=${summary.emptyPages}")
            }
            val notice = if (image) "Image text recognized on this phone (${summary.ocrLabel}). OCR can make mistakes; check the original picture." else summary.notice
            store.status(id, "ready", notice = notice)
            Log.i("Readly", "Document import ready image=$image pages=${summary.pages} characters=${summary.characters} emptyPages=${summary.emptyPages} ocrPages=${summary.ocrPages}")
            stage = "starting playback"
            // Both the text and the service start are local; large books never enter the Binder payload.
            val textId = text.name
            runOnUiThread {
                if (isFinishing || cancelled.get()) {
                    if (!File(cacheDir, "pdf-imports/$textId").delete()) Log.w("Readly", "Couldn't remove cancelled PDF text import")
                } else {
                    try {
                        ReaderService.readPdf(this, textId, title, notice)
                        startActivity(Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        finish()
                    } catch (e: Exception) {
                        Log.e("Readly", "Document import couldn't start playback", e)
                        status.setText(if (image) R.string.image_read_failed else R.string.pdf_read_failed)
                        progress.isIndeterminate = false
                        if (!File(cacheDir, "pdf-imports/$textId").delete()) Log.w("Readly", "Couldn't remove failed PDF text import")
                    }
                }
            }
            handedOff = true
        } catch (e: Exception) {
            Log.e("Readly", "Document import failed image=$image stage=$stage", e)
            var message = when (e) {
                is CancellationException, is InterruptedException -> getString(R.string.pdf_cancelled)
                is PdfImportException -> e.message ?: getString(if (image) R.string.image_read_failed else R.string.pdf_read_failed)
                else -> if (stage == "settings") getString(R.string.pdf_settings_invalid) else getString(if (image) R.string.image_read_failed else R.string.pdf_read_failed)
            }
            if (storage != null && importId != null) {
                try {
                    storage.status(importId, if (cancelled.get()) "cancelled" else "failed", error = message)
                } catch (stateError: Exception) {
                    Log.e("Readly", "Couldn't save document import failure status", stateError)
                    message += " " + getString(R.string.pdf_state_failed)
                }
            }
            runOnUiThread {
                if (!isFinishing) {
                    status.text = message
                    progress.isIndeterminate = false
                    progress.progress = 0
                }
            }
        } finally {
            storage?.close()
            if (!copied) source?.let { if (it.exists() && !it.delete()) Log.w("Readly", "Couldn't remove incomplete PDF source") }
            if (!handedOff) text?.let { if (it.exists() && !it.delete()) Log.w("Readly", "Couldn't remove failed PDF text import") }
        }
    }

    override fun onDestroy() {
        cancelled.set(true)
        executor.shutdownNow()
        super.onDestroy()
    }
}
