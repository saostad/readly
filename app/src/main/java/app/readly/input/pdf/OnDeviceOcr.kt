package app.readly.input.pdf

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.util.concurrent.CancellationException

/** One recognizer for PDF pages, photographs and screenshots. One instance per import thread. */
class OnDeviceOcr(
    private val context: Context,
    private val options: PdfOcrOptions,
    private val cancelled: () -> Boolean,
) : AutoCloseable {
    init { options.validate() }
    private var engine: TessBaseAPI? = null
    private var engineLanguage: String? = null

    data class Result(val text: String, val language: String, val modelId: String, val confidence: Int) {
        val label: String get() = "Tesseract, " + when (language) {
            "eng" -> "English"; "fas" -> "Persian"; else -> "English + Persian"
        }
    }

    fun recognize(bitmap: Bitmap, page: Int, imageLayout: Boolean = false): Result {
        if (cancelled()) throw CancellationException("Document import cancelled")
        val api = engine ?: createEngine().also { engine = it }
        fun pass(language: String): String {
            initialize(api, language)
            if (imageLayout) api.setPageSegMode(options.imagePageSegMode)
            api.setImage(bitmap)
            // HOCR supports stop(); UTF8 reads the recognition result from that same pass.
            api.getHOCRText(page)
            if (cancelled()) throw CancellationException("Document import cancelled")
            return api.utF8Text.orEmpty()
        }
        var language = if (options.languages == "auto") "eng+fas" else options.languages
        val probe = pass(language)
        val text = if (options.languages == "auto") {
            language = options.languageForDraft(probe)
            Log.i("Readly", "OCR automatic language page=${page + 1} language=$language")
            pass(language)
        } else probe
        return Result(text, language, options.copy(languages = language).modelId, api.meanConfidence()).also { api.clear() }
    }

    private fun createEngine(): TessBaseAPI {
        PdfOcrModels.prepare(context, if (options.languages == "auto") "eng+fas" else options.languages, cancelled)
        return TessBaseAPI { _ -> if (cancelled()) engine?.stop() }
    }

    private fun initialize(api: TessBaseAPI, language: String) {
        if (engineLanguage == language) return
        if (!api.init(File(context.filesDir, "pdf-ocr").absolutePath, language, TessBaseAPI.OEM_LSTM_ONLY)) {
            throw PdfImportException("Couldn't initialize OCR. Check the OCR language setting and try again.")
        }
        engineLanguage = language
    }

    override fun close() { engine?.recycle() }
}
