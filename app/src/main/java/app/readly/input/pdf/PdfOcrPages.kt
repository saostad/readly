package app.readly.input.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.Writer
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

/** Uses the PDF text layer first; only textless pages are rasterized and recognized. */
class PdfOcrPages(
    private val context: Context,
    private val file: File,
    private val limits: PdfLimits,
    private val options: PdfOcrOptions,
    private val cancelled: () -> Boolean,
    private val onOcr: (Int, Int) -> Unit,
    private val save: (PdfPageDraft) -> Unit,
) : PdfPages {
    init { limits.validate(); options.validate() }
    private val text = PdfBoxPages(context, file)
    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private val recognizer = OnDeviceOcr(context, options, cancelled)
    private val usedLanguages = linkedSetOf<String>()
    override var ocrPages = 0
        private set
    override val ocrLabel: String get() = "Tesseract, " + usedLanguages.joinToString(", ") {
        when (it) { "eng" -> "English"; "fas" -> "Persian"; else -> "English + Persian" }
    }
    override val count: Int get() = text.count

    override fun writePage(index: Int, output: Writer) {
        val writer = PdfPageWriter(limits.maxTextChars, cancelled)
        text.writePage(index, writer)
        val extracted = writer.toString()
        if (extracted.any { it.isLetterOrDigit() } || !options.enabled) {
            output.write(extracted)
            save(PdfPageDraft(index, extracted, PdfPageSource.TEXT_LAYER, null, null))
            return
        }
        if (cancelled()) throw CancellationException("PDF import cancelled")
        onOcr(index + 1, count)
        Log.w("Readly", "fallback=true attempted=pdf_text_layer reason=no_extractable_text taken=on_device_ocr page=${index + 1}")
        val pdf = renderer ?: run {
            val opened = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            descriptor = opened
            PdfRenderer(opened).also { renderer = it }
        }
        val recognized = pdf.openPage(index).use { page ->
            val scale = options.maxSide.toDouble() / maxOf(page.width, page.height)
            val width = (page.width * scale).roundToInt().coerceAtLeast(1)
            val height = (page.height * scale).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                if (cancelled()) throw CancellationException("PDF import cancelled")
                recognizer.recognize(bitmap, index)
            } finally { bitmap.recycle() }
        }
        ocrPages++
        usedLanguages += recognized.language
        output.write(recognized.text)
        save(PdfPageDraft(index, recognized.text, PdfPageSource.OCR, recognized.modelId, recognized.confidence))
    }

    override fun close() {
        try { text.close() } finally {
            try { renderer?.close() } finally {
                try { descriptor?.close() } finally { recognizer.close() }
            }
        }
    }
}
