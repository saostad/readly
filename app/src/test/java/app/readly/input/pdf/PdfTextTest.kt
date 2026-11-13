package app.readly.input.pdf

import java.io.StringWriter
import java.io.Writer
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PdfTextTest {
    private fun pages(vararg text: String): PdfPages = object : PdfPages {
        override val count = text.size
        override fun writePage(index: Int, output: Writer) { output.write(text[index]) }
        override fun close() = Unit
    }

    @Test fun normalizationPreservesParagraphsSeparatedByWhitespaceOnlyLines() {
        assertEquals("First paragraph wrapped.\n\nSecond paragraph.",
            PdfText.normalizePage("First paragraph\r\nwrapped.\r\n \t\r\nSecond paragraph."))
    }

    @Test fun readsAllPagesInOrderAndJoinsWrappedLines() {
        val output = StringWriter()
        val progress = mutableListOf<Int>()
        val summary = PdfText.extract(pages("First page\nwrapped line.", "صفحه دوم", "Last page."), PdfLimits(), output,
            progress = { page, _ -> progress += page })
        assertEquals("First page wrapped line.\n\nصفحه دوم\n\nLast page.", output.toString())
        assertEquals(listOf(1, 2, 3), progress)
        assertEquals(3, summary.pages)
        assertEquals(0, summary.emptyPages)
        assertEquals(output.toString().length, summary.characters)
    }

    @Test fun recognizesPdfWithMimeOrUppercaseExtension() {
        assertTrue(PdfText.isPdf("application/pdf; charset=binary", null, null))
        assertTrue(PdfText.isPdf("application/octet-stream", "TEXTBOOK.PDF", null))
        assertTrue(PdfText.isPdf(null, null, "application/pdf"))
        assertTrue(PdfText.isPdf("application/x-pdf", null, null))
        assertFalse(PdfText.isPdf("text/plain", "notes.txt", null))
    }

    @Test fun imageOnlyDocumentIsAnErrorAndMixedDocumentDisclosesMissingPages() {
        try {
            PdfText.extract(pages("", "   "), PdfLimits(), StringWriter())
            fail("Scanned PDFs must not look successfully read")
        } catch (e: PdfImportException) {
            assertTrue(e.message!!.contains("OCR"))
        }
        val output = StringWriter()
        val summary = PdfText.extract(pages("Chapter one.", "", "Chapter three."), PdfLimits(), output)
        assertEquals("Chapter one.\n\nChapter three.", output.toString())
        assertEquals(1, summary.emptyPages)
        assertTrue(summary.notice.contains("1 of 3"))
        assertTrue(summary.notice.contains("not read"))
    }

    @Test fun textLimitStopsWhileTheParserWritesAndDoesNotReturnPartialSuccess() {
        val output = StringWriter()
        try {
            PdfText.extract(pages("a".repeat(6000), "b".repeat(6000)), PdfLimits(maxTextChars = 10_000), output)
            fail("Expected the configured limit to fail")
        } catch (e: PdfImportException) {
            assertTrue(e.message!!.contains("exceeds the limit"))
        }
        assertEquals(6000, output.toString().length)
    }

    @Test fun cancellationStopsBeforeAnotherPageIsRead() {
        var completed = 0
        try {
            PdfText.extract(pages("First.", "Second."), PdfLimits(), StringWriter(), { completed == 1 },
                { _, _ -> completed++ })
            fail("Expected cancellation")
        } catch (e: CancellationException) {
            assertEquals(1, completed)
        }
    }

    @Test fun limitsAreValidatedAndNoPagesIsAnError() {
        PdfLimits().validate()
        for (limits in listOf(PdfLimits(0), PdfLimits(1025), PdfLimits(maxTextChars = 9999))) {
            try { limits.validate(); fail("Expected invalid settings") } catch (e: IllegalArgumentException) {
                assertFalse(e.message.isNullOrBlank())
            }
        }
        try {
            PdfText.extract(pages(), PdfLimits(), StringWriter())
            fail("Expected an empty PDF error")
        } catch (e: PdfImportException) { assertTrue(e.message!!.contains("no pages")) }
    }

    @Test fun fileCopyChecksActualBytesAndCancellation() {
        val dir = java.nio.file.Files.createTempDirectory("readly-pdf-test-").toFile()
        val file = java.io.File(dir, "input.pdf")
        try {
            val bytes = ByteArray(1024 * 1024 + 1)
            try {
                bytes.inputStream().use { PdfFiles.copy(it, file, PdfLimits(maxFileMb = 1)) { false } }
                fail("Expected a file-size error, not a truncated success")
            } catch (e: PdfImportException) { assertTrue(e.message!!.contains("1 MB")) }
            assertTrue(file.length() <= 1024 * 1024)
            try {
                byteArrayOf(1, 2, 3).inputStream().use { PdfFiles.copy(it, file, PdfLimits()) { true } }
                fail("Expected cancellation")
            } catch (e: CancellationException) { assertEquals(0, file.length()) }
        } finally {
            java.nio.file.Files.deleteIfExists(file.toPath())
            java.nio.file.Files.deleteIfExists(dir.toPath())
        }
    }

    @Test fun ocrUseIsVisibleAndSettingsValidate() {
        val scanned = object : PdfPages by pages("Recognized chapter.") {
            override val ocrPages = 1
            override val ocrLabel = "Tesseract, English + Persian"
        }
        val summary = PdfText.extract(scanned, PdfLimits(), StringWriter())
        assertTrue(summary.notice.contains("OCR used on 1"))
        assertTrue(summary.notice.contains("Tesseract"))
        assertTrue(summary.notice.contains("errors"))
        PdfOcrOptions().validate()
        for (options in listOf(PdfOcrOptions(languages = "unknown"), PdfOcrOptions(maxSide = 799), PdfOcrOptions(maxSide = 4001))) {
            try { options.validate(); fail("Expected invalid OCR settings") }
            catch (e: IllegalArgumentException) { assertFalse(e.message.isNullOrBlank()) }
        }
    }

    @Test fun automaticOcrSelectsThePageLanguageWithoutOverridingExplicitChoices() {
        val auto = PdfOcrOptions()
        assertEquals("fas", auto.languageForDraft("خواندن کتاب بسیار خوب است GUS"))
        assertEquals("eng", auto.languageForDraft("This is an English textbook page."))
        assertEquals("eng", auto.languageForDraft("Exit ۱۲۳۴۵۶۷۸۹۰،؛؟"))
        assertEquals("fas", auto.languageForDraft("خروج 1234567890"))
        assertEquals("eng+fas", PdfOcrOptions(languages = "eng+fas").languageForDraft("خواندن کتاب"))
        assertEquals("fas", PdfOcrOptions(languages = "fas").languageForDraft("An English word"))
    }
}
