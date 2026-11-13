package app.readly.input.pdf

import java.io.Writer
import java.util.concurrent.CancellationException

/** PDF import settings, shared by the importer and settings UI. */
data class PdfLimits(val maxFileMb: Int = 200, val maxTextChars: Int = 2_000_000) {
    val maxFileBytes: Long get() = maxFileMb * 1024L * 1024L

    fun validate() {
        require(maxFileMb in 1..1024) { "Document file limit must be between 1 and 1024 MB." }
        require(maxTextChars in 10_000..20_000_000) { "Document text limit must be between 10,000 and 20,000,000 characters." }
    }
}

class PdfImportException(message: String) : java.io.IOException(message)

interface PdfPages : AutoCloseable {
    val count: Int
    val ocrPages: Int get() = 0
    val ocrLabel: String get() = ""
    /** Implementations must stop writing when [output] throws (including the text limit). */
    fun writePage(index: Int, output: Writer)
}

data class PdfSummary(val pages: Int, val emptyPages: Int, val characters: Int, val ocrPages: Int = 0, val ocrLabel: String = "") {
    private val ocrNotice: String get() = if (ocrPages == 0) "" else " OCR used on $ocrPages pages ($ocrLabel); recognition may contain errors."
    val notice: String get() = (if (emptyPages == 0) {
        "PDF: $pages pages. Reading order depends on the PDF's text layout."
    } else {
        "PDF: $emptyPages of $pages pages had no readable text and were not read. " +
            "Some pages may be blank or unreadable. Reading order depends on the PDF's text layout."
    }) + ocrNotice
}

/** Bounds extraction while the PDF parser writes, before a page-sized string is allocated. */
internal class PdfPageWriter(private val limit: Int, private val cancelled: () -> Boolean) : Writer() {
    private val text = StringBuilder()
    override fun write(chars: CharArray, offset: Int, length: Int) {
        if (cancelled()) throw CancellationException("PDF import cancelled")
        if (length > limit - text.length) {
            throw PdfImportException("Extracted text exceeds the limit. Increase the document text limit in Voice & speed, or share a smaller section.")
        }
        text.append(chars, offset, length)
    }
    override fun flush() = Unit
    override fun close() = Unit
    override fun toString(): String = text.toString()
}

/** One extraction service for UI and other clients; page boundaries remain paragraph breaks. */
object PdfText {
    fun isPdf(type: String?, name: String?, mime: String?): Boolean =
        listOf(type, mime).any { it?.substringBefore(';')?.trim()?.lowercase() in setOf("application/pdf", "application/x-pdf") } ||
            name?.endsWith(".pdf", true) == true

    fun extract(
        pages: PdfPages,
        limits: PdfLimits,
        output: Writer,
        cancelled: () -> Boolean = { false },
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): PdfSummary {
        limits.validate()
        if (pages.count <= 0) throw PdfImportException("This PDF has no pages to read.")
        var characters = 0
        var empty = 0
        for (page in 0 until pages.count) {
            if (cancelled()) throw CancellationException("PDF import cancelled")
            val writer = PdfPageWriter(limits.maxTextChars - characters, cancelled)
            pages.writePage(page, writer)
            if (cancelled()) throw CancellationException("PDF import cancelled")
            val text = normalizePage(writer.toString())
            if (text.none { it.isLetterOrDigit() }) {
                empty++
            } else {
                val separator = if (characters == 0) "" else "\n\n"
                if (text.length + separator.length > limits.maxTextChars - characters) {
                    throw PdfImportException("Extracted text exceeds the limit. Increase the document text limit in Voice & speed, or share a smaller section.")
                }
                output.write(separator)
                output.write(text)
                characters += separator.length + text.length
            }
            progress(page + 1, pages.count)
        }
        if (characters == 0) {
            throw PdfImportException("No readable text was found. Check the OCR language and settings in Voice & speed, or try a clearer picture or scan.")
        }
        return PdfSummary(pages.count, empty, characters, pages.ocrPages, pages.ocrLabel)
    }

    internal fun normalizePage(raw: String): String = raw.replace("\r\n", "\n").replace('\r', '\n')
        .replace('\u0000', ' ').replace('\u000c', '\n')
        .split(Regex("\n[\t ]*\n"))
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")
}
