package app.readly.input.pdf

import app.readly.playback.ReadingProgress
import app.readly.text.Chunk
import app.readly.text.TextSanitizer
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

/** One saved page from [PdfImportStore], where [page] is the 0-based PDF page index. */
data class PdfPageText(val page: Int, val text: String)

/** Where a [Chunk] sits in a PDF: 1-based [page], 0-based [pageSentenceIndex] within that page, and cumulative [offsetChars]. */
data class PdfChunkLocation(
    val chunkIndex: Int,
    val page: Int,
    val pageSentenceIndex: Int,
    val offsetChars: Int,
)

/** Saved reading position for an imported document. [lastPage] is 1-based (0 when never started). */
data class PdfReadingProgress(
    val lastPage: Int = 0,
    val lastSentence: Int = 0,
    val lastPageSentence: Int = 0,
    val lastOffsetChars: Int = 0,
    val totalSentences: Int = 0,
    val finished: Boolean = false,
) {
    val hasResumePoint: Boolean
        get() = !finished && (lastSentence > 0 || lastPageSentence > 0 || lastOffsetChars > 0 || lastPage > 1)
}

/** One extraction service for UI and other clients; page boundaries remain paragraph breaks. */
object PdfText {
    fun isPdf(type: String?, name: String?, mime: String?): Boolean =
        listOf(type, mime).any { it?.substringBefore(';')?.trim()?.lowercase() in setOf("application/pdf", "application/x-pdf") } ||
            name?.endsWith(".pdf", true) == true

    fun hasReadableText(normalizedPage: String): Boolean = normalizedPage.any { it.isLetterOrDigit() }

    fun extract(
        pages: PdfPages,
        limits: PdfLimits,
        output: Writer,
        cancelled: () -> Boolean = { false },
        progress: (Int, Int) -> Unit = { _, _ -> },
        startPage: Int = 1,
    ): PdfSummary {
        limits.validate()
        if (pages.count <= 0) throw PdfImportException("This PDF has no pages to read.")
        if (startPage !in 1..pages.count) throw PdfImportException("Starting page must be between 1 and ${pages.count}.")
        var characters = 0
        var empty = 0
        for (page in (startPage - 1) until pages.count) {
            if (cancelled()) throw CancellationException("PDF import cancelled")
            val writer = PdfPageWriter(limits.maxTextChars - characters, cancelled)
            pages.writePage(page, writer)
            if (cancelled()) throw CancellationException("PDF import cancelled")
            val text = normalizePage(writer.toString())
            if (!hasReadableText(text)) {
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

    /**
     * Maps each chunk in [chunks] to its 1-based PDF page number and 0-based statement
     * index within that page. Blank/unreadable pages are skipped, preserving the true
     * 1-based PDF page numbers of the remaining pages.
     */
    fun locateChunks(
        pages: List<PdfPageText>,
        chunks: List<Chunk>,
        readHiddenText: Boolean = false,
    ): List<PdfChunkLocation> {
        if (chunks.isEmpty()) return emptyList()
        val readable = pages.sortedBy { it.page }.filter { hasReadableText(it.text) }
        val joinedRaw = readable.joinToString("\n\n") { it.text }
        val persian = TextSanitizer.isPersianText(joinedRaw)
        data class PageSpan(val pageNumber: Int, val start: Int, val end: Int)
        val spans = mutableListOf<PageSpan>()
        val joinedClean = StringBuilder()
        for (p in readable) {
            val cleanPage = TextSanitizer.sanitizePlain(p.text, readHiddenText, persian)
            if (cleanPage.isEmpty()) continue
            if (joinedClean.isNotEmpty()) joinedClean.append("\n\n")
            val start = joinedClean.length
            joinedClean.append(cleanPage)
            spans.add(PageSpan(p.page + 1, start, joinedClean.length))
        }
        val fullClean = joinedClean.toString()
        var searchCursor = 0
        var offsetChars = 0
        val pageCounts = mutableMapOf<Int, Int>()
        return chunks.mapIndexed { index, chunk ->
            val found = if (fullClean.isNotEmpty() && chunk.text.isNotEmpty()) {
                fullClean.indexOf(chunk.text, searchCursor).takeIf { it >= 0 }
            } else {
                null
            }
            val pos = found ?: chunk.start.coerceAtLeast(searchCursor)
            if (found != null) searchCursor = found + chunk.text.length
            val span = spans.lastOrNull { it.start <= pos } ?: spans.firstOrNull()
            val pageNumber = span?.pageNumber ?: 1
            val sentenceInPage = pageCounts.getOrDefault(pageNumber, 0)
            pageCounts[pageNumber] = sentenceInPage + 1
            val location = PdfChunkLocation(
                chunkIndex = index,
                page = pageNumber,
                pageSentenceIndex = sentenceInPage,
                offsetChars = offsetChars,
            )
            offsetChars += chunk.text.length
            location
        }
    }

    /**
     * Resolves the statement [PdfChunkLocation] to continue from for [progress], or null
     * when the document has not been started, was finished, or resolves to statement 0.
     */
    fun resolveResume(
        progress: PdfReadingProgress,
        locations: List<PdfChunkLocation>,
        chunks: List<Chunk>,
    ): PdfChunkLocation? {
        if (!progress.hasResumePoint || chunks.isEmpty() || locations.size != chunks.size) return null
        val candidate: PdfChunkLocation = when {
            progress.totalSentences == chunks.size &&
                progress.lastSentence in locations.indices &&
                (progress.lastPage <= 0 || locations[progress.lastSentence].page == progress.lastPage) ->
                locations[progress.lastSentence]
            progress.lastPage > 0 -> {
                val onPage = locations.filter { it.page == progress.lastPage }
                when {
                    onPage.isNotEmpty() -> onPage[progress.lastPageSentence.coerceIn(0, onPage.lastIndex)]
                    else -> {
                        val afterPage = locations.firstOrNull { it.page > progress.lastPage }
                        if (afterPage != null) {
                            afterPage
                        } else if (progress.lastOffsetChars > 0) {
                            val idx = ReadingProgress.indexAt(
                                chunks.map { it.text.length },
                                progress.lastOffsetChars.toLong(),
                                1.0,
                            )
                            locations[idx.coerceIn(0, locations.lastIndex)]
                        } else {
                            locations[progress.lastSentence.coerceIn(0, locations.lastIndex)]
                        }
                    }
                }
            }
            progress.lastOffsetChars > 0 -> {
                val idx = ReadingProgress.indexAt(
                    chunks.map { it.text.length },
                    progress.lastOffsetChars.toLong(),
                    1.0,
                )
                locations[idx.coerceIn(0, locations.lastIndex)]
            }
            else -> locations[progress.lastSentence.coerceIn(0, locations.lastIndex)]
        }
        return candidate.takeIf { it.chunkIndex > 0 }
    }

    /**
     * Resolves the first [PdfChunkLocation] on 1-based [startPage]. If [startPage] has
     * no readable text, advances to the first readable page after [startPage] (or falls
     * back to the first statement of the last readable page when [startPage] exceeds it).
     */
    fun resolveStartPage(
        startPage: Int,
        locations: List<PdfChunkLocation>,
    ): PdfChunkLocation? {
        if (startPage <= 0 || locations.isEmpty()) return null
        locations.firstOrNull { it.page == startPage }?.let { return it }
        locations.firstOrNull { it.page > startPage }?.let { return it }
        val lastReadablePage = locations.maxOf { it.page }
        return locations.firstOrNull { it.page == lastReadablePage }
    }

    internal fun normalizePage(raw: String): String = raw.replace("\r\n", "\n").replace('\r', '\n')
        .replace('\u0000', ' ').replace('\u000c', '\n')
        .split(Regex("\n[\t ]*\n"))
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")
}
