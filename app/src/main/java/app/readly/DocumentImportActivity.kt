package app.readly

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import app.readly.input.pdf.PdfBoxPages
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Keeps the URI grant alive while a shared document/image is imported off the UI thread. */
class DocumentImportActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var pagePanel: LinearLayout
    @Volatile
    private var pendingStartChoice: CountDownLatch? = null
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
        pagePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        box.addView(pagePanel)
        box.addView(Button(this).apply {
            setText(android.R.string.cancel)
            setOnClickListener {
                cancelled.set(true)
                pendingStartChoice?.countDown()
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
            val kind = if (image) "image" else "pdf"
            text = PdfFiles.newText(this)
            val id = text.name.removeSuffix(".txt")
            importId = id
            val store = PdfImportStore(this)
            storage = store
            store.create(id, title, kind)
            source = store.sourceFile(id, kind)
            stage = "copying"
            val sha256 = contentResolver.openInputStream(uri)?.use { input ->
                PdfFiles.copy(input, source, limits) { cancelled.get() || Thread.currentThread().isInterrupted }
            } ?: throw PdfImportException("Couldn't open the shared file. Share it again from the original app.")
            copied = true
            val existing = store.findReadyBySha256(sha256, kind, source.length())
            val chosenStartPage = if (!image) {
                val totalPages = if (existing != null && existing.pageCount > 0) existing.pageCount
                else PdfBoxPages(this, source).use { it.count }
                if (totalPages > 1) promptStartPage(totalPages, existing) else 0
            } else {
                0
            }
            var handoffTitle = title
            var handoffNotice = ""
            var handoffDocumentId = id
            if (existing != null) {
                if (cancelled.get()) throw CancellationException("Document import cancelled")
                store.discardImport(id, kind)
                importId = null
                source = null
                copied = false
                store.writeText(existing.id, limits, text)
                handoffTitle = existing.title
                handoffNotice = existing.notice
                handoffDocumentId = existing.id
                Log.i("Readly", "Reusing ready document import id=${existing.id} kind=$kind")
            } else {
                store.setSha256(id, sha256)
                stage = "extracting"
                store.status(id, "extracting")
                val srcFile = source
                val summary = text.bufferedWriter().use { output ->
                    val isCancelled = { cancelled.get() || Thread.currentThread().isInterrupted }
                    val pages: PdfPages = if (image) {
                        SharedImagePages(this, srcFile, ocr, isCancelled,
                            { store.status(id, "recognizing")
                                runOnUiThread { if (!isFinishing) status.setText(R.string.image_recognizing) } },
                            { draft -> store.savePage(id, draft, limits) })
                    } else {
                        PdfOcrPages(this, srcFile, limits, ocr, isCancelled,
                            { page, count -> store.status(id, "recognizing")
                                runOnUiThread { if (!isFinishing) status.text = getString(R.string.pdf_recognizing, page, count) } },
                            { draft -> store.savePage(id, draft, limits) },
                            ocrStartPage = chosenStartPage.coerceAtLeast(1))
                    }
                    pages.use { document ->
                        PdfText.extract(
                            document,
                            limits,
                            output,
                            { cancelled.get() || Thread.currentThread().isInterrupted },
                            progress = { page, count ->
                                store.status(id, "extracting")
                                runOnUiThread {
                                    if (!isFinishing) {
                                        status.text = getString(R.string.pdf_extracting, page, count)
                                        progress.isIndeterminate = false
                                        progress.max = count
                                        progress.progress = page
                                    }
                                }
                            },
                        )
                    }
                }
                if (cancelled.get()) throw CancellationException("Document import cancelled")
                if (summary.emptyPages > 0) {
                    Log.w("Readly", "fallback=true attempted=pdf_page_text reason=no_extractable_text taken=skip_empty_pages pages=${summary.pages} emptyPages=${summary.emptyPages}")
                }
                val notice = if (image) "Image text recognized on this phone (${summary.ocrLabel}). OCR can make mistakes; check the original picture." else summary.notice
                store.status(id, "ready", notice = notice)
                handoffNotice = notice
                Log.i("Readly", "Document import ready image=$image pages=${summary.pages} characters=${summary.characters} emptyPages=${summary.emptyPages} ocrPages=${summary.ocrPages}")
            }
            stage = "starting playback"
            // Both the text and the service start are local; large books never enter the Binder payload.
            val textId = text.name
            runOnUiThread {
                if (isFinishing || cancelled.get()) {
                    if (!File(cacheDir, "pdf-imports/$textId").delete()) Log.w("Readly", "Couldn't remove cancelled PDF text import")
                } else {
                    try {
                        ReaderService.readPdf(this, textId, handoffTitle, handoffNotice, handoffDocumentId, chosenStartPage)
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

    private fun promptStartPage(totalPages: Int, existing: PdfImportStore.Import?): Int {
        if (cancelled.get()) throw CancellationException("PDF import cancelled")
        val latch = CountDownLatch(1)
        val choice = AtomicInteger(0)
        pendingStartChoice = latch
        runOnUiThread {
            if (isFinishing || isDestroyed || cancelled.get()) {
                latch.countDown()
                return@runOnUiThread
            }
            val density = resources.displayMetrics.density
            val marginTop = (12 * density).toInt()
            val docTitle = existing?.title
                ?: intent.getStringExtra(Intent.EXTRA_TITLE)?.substringBeforeLast('.')?.ifBlank { null }
                ?: getString(R.string.pdf_default_title)
            status.text = getString(R.string.pdf_start_from_page_range, docTitle, totalPages)
            progress.visibility = View.GONE
            pagePanel.removeAllViews()
            pagePanel.addView(TextView(this, null, 0, R.style.Body).apply {
                text = getString(R.string.pdf_start_from_page_input_label, totalPages)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = marginTop }
            })
            val defaultPage = (existing?.lastPage?.takeIf { it > 0 } ?: 1).coerceIn(1, totalPages)
            val input = EditText(this, null, 0, R.style.BoxEdit).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(defaultPage.toString())
                setSelectAllOnFocus(true)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (8 * density).toInt() }
            }
            pagePanel.addView(input)
            fun confirmEnteredPage() {
                val entered = input.text.toString().trim().toIntOrNull()
                if (entered == null || entered !in 1..totalPages) {
                    Toast.makeText(this, getString(R.string.pdf_start_from_page_invalid, totalPages), Toast.LENGTH_SHORT).show()
                    return
                }
                choice.set(entered)
                pagePanel.visibility = View.GONE
                pagePanel.removeAllViews()
                progress.visibility = View.VISIBLE
                latch.countDown()
            }
            if (existing != null && existing.progress.hasResumePoint) {
                val resumeLabel = existing.progressLabel() ?: "Page ${existing.lastPage}"
                pagePanel.addView(Button(this, null, 0, R.style.Btn_Primary).apply {
                    text = getString(R.string.pdf_continue_reading_format, resumeLabel)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = marginTop }
                    setOnClickListener {
                        choice.set(0)
                        pagePanel.visibility = View.GONE
                        pagePanel.removeAllViews()
                        progress.visibility = View.VISIBLE
                        latch.countDown()
                    }
                })
                pagePanel.addView(Button(this, null, 0, R.style.Btn_Ghost).apply {
                    setText(R.string.pdf_start_at_selected_page)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = (8 * density).toInt() }
                    setOnClickListener { confirmEnteredPage() }
                })
            } else {
                pagePanel.addView(Button(this, null, 0, R.style.Btn_Primary).apply {
                    setText(R.string.pdf_start_reading)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = marginTop }
                    setOnClickListener { confirmEnteredPage() }
                })
            }
            pagePanel.visibility = View.VISIBLE
        }
        try {
            latch.await()
        } finally {
            if (pendingStartChoice === latch) pendingStartChoice = null
        }
        if (cancelled.get()) throw CancellationException("PDF import cancelled")
        return choice.get()
    }

    override fun onDestroy() {
        cancelled.set(true)
        pendingStartChoice?.countDown()
        executor.shutdownNow()
        super.onDestroy()
    }
}
