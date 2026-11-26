package app.readly.input.pdf

import app.readly.text.Chunk
import java.io.ByteArrayInputStream
import java.io.StringWriter
import java.io.Writer
import java.nio.file.Files
import java.security.MessageDigest
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

    @Test fun locateChunksTracksOneBasedPagesBlankSkipsAndDuplicates() {
        val pages = listOf(
            PdfPageText(0, "First page intro. Repeated sentence."),
            PdfPageText(1, "   \n\t "),
            PdfPageText(2, "Third page opening. Repeated sentence. Closing remark.")
        )
        val joined = "First page intro. Repeated sentence.\n\nThird page opening. Repeated sentence. Closing remark."
        val s0 = "First page intro."
        val s1 = "Repeated sentence."
        val s2 = "Third page opening."
        val s3 = "Repeated sentence."
        val s4 = "Closing remark."
        var cursor = 0
        val chunks = listOf(s0, s1, s2, s3, s4).map { sentence ->
            val start = joined.indexOf(sentence, cursor)
            cursor = start + sentence.length
            Chunk(sentence, start, cursor)
        }

        val locations = PdfText.locateChunks(pages, chunks)
        assertEquals(5, locations.size)
        assertEquals(listOf(1, 1, 3, 3, 3), locations.map { it.page })
        assertEquals(listOf(0, 1, 0, 1, 2), locations.map { it.pageSentenceIndex })
        for (i in 1 until locations.size) {
            assertTrue("offsetChars should advance for chunk $i", locations[i].offsetChars > locations[i - 1].offsetChars)
        }
    }

    @Test fun locateChunksHandlesZeroBasedReadingStoreChunksWithDuplicatePages() {
        val pages = listOf(
            PdfPageText(0, "Alpha line. Shared line."),
            PdfPageText(1, "Shared line. Beta line.")
        )
        val chunks = listOf("Alpha line.", "Shared line.", "Shared line.", "Beta line.")
            .map { Chunk(it, 0, it.length) }

        val locations = PdfText.locateChunks(pages, chunks)
        assertEquals(4, locations.size)
        assertEquals(listOf(1, 1, 2, 2), locations.map { it.page })
        assertEquals(listOf(0, 1, 0, 1), locations.map { it.pageSentenceIndex })
        for (i in 1 until locations.size) {
            assertTrue(locations[i].offsetChars > locations[i - 1].offsetChars)
        }
    }

    @Test fun resolveResumeContinuesFromSavedStatementOrFallsBackByPage() {
        val pages = listOf(
            PdfPageText(0, "Page one first. Page one second."),
            PdfPageText(1, "Page two first. Page two second. Page two third.")
        )
        val chunks = listOf(
            "Page one first.",
            "Page one second.",
            "Page two first.",
            "Page two second.",
            "Page two third."
        ).map { Chunk(it, 0, it.length) }
        val locations = PdfText.locateChunks(pages, chunks)

        val exact = PdfText.resolveResume(
            PdfReadingProgress(
                lastPage = 2,
                lastSentence = 3,
                lastPageSentence = 1,
                lastOffsetChars = locations[3].offsetChars,
                totalSentences = locations.size,
                finished = false
            ),
            locations,
            chunks
        )
        assertNotNull(exact)
        assertEquals(3, exact!!.chunkIndex)
        assertEquals(2, exact.page)
        assertEquals(1, exact.pageSentenceIndex)

        val rechunked = PdfText.resolveResume(
            PdfReadingProgress(
                lastPage = 2,
                lastSentence = 12,
                lastPageSentence = 1,
                lastOffsetChars = locations[3].offsetChars,
                totalSentences = 20,
                finished = false
            ),
            locations,
            chunks
        )
        assertNotNull(rechunked)
        assertEquals(2, rechunked!!.page)
        assertEquals(3, rechunked.chunkIndex)
    }

    @Test fun resolveResumeReturnsNullWhenFinishedOrAtStart() {
        val pages = listOf(PdfPageText(0, "Start sentence. Second sentence."))
        val chunks = listOf("Start sentence.", "Second sentence.").map { Chunk(it, 0, it.length) }
        val locations = PdfText.locateChunks(pages, chunks)

        assertNull(
            PdfText.resolveResume(
                PdfReadingProgress(
                    lastPage = 1,
                    lastSentence = 1,
                    lastPageSentence = 1,
                    lastOffsetChars = locations[1].offsetChars,
                    totalSentences = locations.size,
                    finished = true
                ),
                locations,
                chunks
            )
        )
        assertNull(PdfText.resolveResume(PdfReadingProgress(), locations, chunks))
        assertNull(
            PdfText.resolveResume(
                PdfReadingProgress(lastPage = 1, lastSentence = 0, lastPageSentence = 0, lastOffsetChars = 0, totalSentences = 2),
                locations,
                chunks
            )
        )
    }

    @Test fun pdfFilesCopyReturnsLowercaseSha256HexDigest() {
        val data = "%PDF-1.4\nSample PDF content for resume hashing.\n".toByteArray(Charsets.UTF_8)
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(data)
            .joinToString("") { "%02x".format(it) }
        val tempFile = Files.createTempFile("readly-pdf-copy-test", ".pdf").toFile()
        try {
            val digest = PdfFiles.copy(ByteArrayInputStream(data), tempFile, PdfLimits()) { false }
            assertEquals(64, digest.length)
            assertEquals(expected, digest)
            assertArrayEquals(data, tempFile.readBytes())
            assertEquals(expected, PdfFiles.sha256(tempFile))
        } finally {
            tempFile.delete()
        }
    }

    @Test fun resolveStartPageFindsPageSkipsBlankClampsAndHandlesEmpty() {
        val pages = listOf(
            PdfPageText(0, "Page one text."),
            PdfPageText(1, "   "),
            PdfPageText(2, "Page three first. Page three second."),
            PdfPageText(3, "Page four text."),
        )
        val chunks = listOf(
            "Page one text.",
            "Page three first.",
            "Page three second.",
            "Page four text.",
        ).map { Chunk(it, 0, it.length) }
        val locations = PdfText.locateChunks(pages, chunks)

        assertNull(PdfText.resolveStartPage(0, locations))
        assertNull(PdfText.resolveStartPage(-1, locations))
        assertNull(PdfText.resolveStartPage(1, emptyList()))

        val exact = PdfText.resolveStartPage(3, locations)
        assertNotNull(exact)
        assertEquals(1, exact!!.chunkIndex)
        assertEquals(3, exact.page)
        assertEquals(0, exact.pageSentenceIndex)

        val skippedBlank = PdfText.resolveStartPage(2, locations)
        assertNotNull(skippedBlank)
        assertEquals(3, skippedBlank!!.page)
        assertEquals(1, skippedBlank.chunkIndex)

        val beyondEnd = PdfText.resolveStartPage(99, locations)
        assertNotNull(beyondEnd)
        assertEquals(4, beyondEnd!!.page)
        assertEquals(3, beyondEnd.chunkIndex)
    }

    @Test fun extractFromStartPageSkipsEarlierPagesAndValidatesBounds() {
        val output = StringWriter()
        val progress = mutableListOf<Int>()
        val summary = PdfText.extract(
            pages("Index page.", "Introduction.", "Section two starts here.", "Conclusion."),
            PdfLimits(),
            output,
            progress = { page, _ -> progress += page },
            startPage = 3,
        )
        assertEquals("Section two starts here.\n\nConclusion.", output.toString())
        assertEquals(listOf(3, 4), progress)
        assertEquals(4, summary.pages)
        assertEquals(0, summary.emptyPages)

        try {
            PdfText.extract(pages("One.", "Two."), PdfLimits(), StringWriter(), startPage = 0)
            fail("Expected PdfImportException for startPage = 0")
        } catch (expected: PdfImportException) {
            assertTrue(expected.message!!.contains("between 1 and 2"))
        }
        try {
            PdfText.extract(pages("One.", "Two."), PdfLimits(), StringWriter(), startPage = 3)
            fail("Expected PdfImportException for startPage > count")
        } catch (expected: PdfImportException) {
            assertTrue(expected.message!!.contains("between 1 and 2"))
        }
    }
}
