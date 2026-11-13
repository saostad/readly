package app.readly.input.pdf

import app.readly.text.TextSanitizer

/** User-controlled OCR settings, not implicit alternate providers. */
data class PdfOcrOptions(val enabled: Boolean = true, val languages: String = "auto", val maxSide: Int = 2400, val imageLayout: String = "auto") {
    fun validate() {
        require(languages in listOf("auto", "eng", "fas", "eng+fas")) { "Choose automatic, English, Persian, or English + Persian for OCR." }
        require(imageLayout in listOf("sparse", "auto", "block", "line")) { "Choose a valid image OCR layout in Voice & speed." }
        require(maxSide in 800..4000) { "OCR page resolution must be between 800 and 4000 pixels." }
    }
    fun languageForDraft(text: String): String {
        validate()
        return if (languages != "auto") languages else if (TextSanitizer.isPersianText(text.filter { it.isLetter() })) "fas" else "eng"
    }
    /** Tesseract's documented page-segmentation protocol values. */
    val imagePageSegMode: Int get() = when (imageLayout) { "auto" -> 3; "block" -> 6; "line" -> 7; else -> 11 }
    val languageLabel: String get() = when (languages) { "auto" -> "Automatic (English/Persian)"; "eng" -> "English"; "fas" -> "Persian"; else -> "English + Persian" }
    val modelId: String get() = "tesseract-5.5.1/tessdata_fast-87416418657359cb625c412a48b6e1d6d41c29bd/$languages"
}

enum class PdfPageSource { TEXT_LAYER, OCR }

data class PdfPageDraft(val page: Int, val text: String, val source: PdfPageSource, val modelId: String?, val confidence: Int?)
