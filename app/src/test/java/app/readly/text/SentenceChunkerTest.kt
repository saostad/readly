package app.readly.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceChunkerTest {

    @Test
    fun chunk_empty_returnsEmptyList() {
        assertEquals(emptyList<Chunk>(), SentenceChunker.chunk(""))
        assertEquals(emptyList<Chunk>(), SentenceChunker.chunk("  \n\n "))
    }

    @Test
    fun chunk_singleSentence_returnsItWhole() {
        assertEquals(
            listOf(Chunk("Hello world.", 0, 12)),
            SentenceChunker.chunk("Hello world."),
        )
    }

    @Test
    fun chunk_offsets_roundTrip() {
        val input = "First para one. Two here.\n\nSecond para here. More words.\n\n  Third has 3.14 in it.  "
        val chunks = SentenceChunker.chunk(input)
        assertTrue(chunks.isNotEmpty())
        var lastEnd = 0
        for (chunk in chunks) {
            assertEquals(chunk.text, input.substring(chunk.start, chunk.end).trim())
            assertTrue(chunk.start < chunk.end)
            assertTrue(chunk.start >= lastEnd)
            lastEnd = chunk.end
        }
    }

    @Test
    fun chunk_firstChunk_isCutTo60Chars() {
        val first = ("word ".repeat(40).trim() + ".")
        assertEquals(200, first.length)
        val input = "$first Second sentence here."
        val chunks = SentenceChunker.chunk(input)
        assertEquals(3, chunks.size)
        assertTrue(chunks[0].text.length <= 60)
        assertTrue(chunks[0].text.endsWith("word"))
        assertEquals(first, chunks[0].text + " " + chunks[1].text)
        assertEquals("Second sentence here.", chunks[2].text)
        for (chunk in chunks) {
            assertEquals(chunk.text, input.substring(chunk.start, chunk.end).trim())
        }
    }

    @Test
    fun chunk_firstSentenceAt80Chars_staysWhole() {
        val eighty = "a".repeat(79) + "."
        assertEquals(80, eighty.length)
        assertEquals(listOf(eighty), SentenceChunker.chunk(eighty).map { it.text })
        val eightyOne = "a".repeat(80) + "."
        assertEquals(2, SentenceChunker.chunk(eightyOne).size)
    }

    @Test
    fun chunk_firstChunk_cutsAfterCommaInWindow() {
        val first = "a".repeat(45) + ", " + "b".repeat(92) + "."
        assertEquals(140, first.length)
        val chunks = SentenceChunker.chunk(first)
        assertEquals(2, chunks.size)
        assertEquals("a".repeat(45) + ",", chunks[0].text)
        assertEquals(first, chunks[0].text + " " + chunks[1].text)
        for (chunk in chunks) {
            assertEquals(chunk.text, first.substring(chunk.start, chunk.end).trim())
        }
    }

    @Test
    fun chunk_firstChunkWithoutSpace_hardCutsAt60() {
        val first = "X" + "x".repeat(138) + "."
        assertEquals(140, first.length)
        val chunks = SentenceChunker.chunk(first)
        assertEquals(2, chunks.size)
        assertEquals(60, chunks[0].text.length)
        assertEquals(first, chunks[0].text + chunks[1].text)
        for (chunk in chunks) {
            assertEquals(chunk.text, first.substring(chunk.start, chunk.end).trim())
        }
    }

    @Test
    fun chunk_shortSentences_mergeForwardTo250Chars() {
        // First sentence is 20 chars, so the tiny-first merge (< 20) doesn't fire;
        // this still exercises the normal < 40-char merge for the rest.
        val chunks = SentenceChunker.chunk("Hello there, friend. OK. This is a longer sentence here.")
        assertEquals(
            listOf("Hello there, friend.", "OK. This is a longer sentence here."),
            chunks.map { it.text },
        )
    }

    @Test
    fun chunk_longSentenceWithComma_splitsAfterComma() {
        // Capital "A" so BreakIterator really starts a new sentence here.
        val long = "A" + "a".repeat(289) + ", b" + "c".repeat(50) + "."
        val chunks = SentenceChunker.chunk("Intro here. $long")
        assertEquals(3, chunks.size)
        assertEquals("Intro here.", chunks[0].text)
        assertTrue(chunks[1].text.endsWith(","))
        assertTrue(chunks[1].text.length <= 300)
        assertEquals(long, chunks[1].text + " " + chunks[2].text)
    }

    @Test
    fun chunk_longSentenceWithoutPunctuation_splitsAtSpace() {
        // Capital "Word" so BreakIterator really starts a new sentence here.
        // Long intro (26 chars) so the tiny-first merge doesn't fire.
        val long = "Word " + "word ".repeat(79) + "tail."
        assertTrue(long.length > 300)
        val chunks = SentenceChunker.chunk("This introduction is here. $long")
        assertEquals(3, chunks.size)
        assertEquals("This introduction is here.", chunks[0].text)
        assertTrue(chunks[1].text.length <= 300)
        assertTrue(chunks[2].text.length <= 300)
        assertEquals(long, chunks[1].text + " " + chunks[2].text)
    }

    @Test
    fun chunk_longWord_hardCuts() {
        // Capital "X" so BreakIterator really starts a new sentence here.
        val chunks = SentenceChunker.chunk("Intro. " + "X" + "x".repeat(349) + ".")
        assertEquals(3, chunks.size)
        assertEquals("Intro.", chunks[0].text)
        assertEquals(300, chunks[1].text.length)
        assertEquals(51, chunks[2].text.length)
    }

    @Test
    fun chunk_abbreviationsBeforeLowercaseOrDigit_stayGlued() {
        assertEquals(
            listOf("Bring snacks, e.g. chips and dip.", "Everyone shares."),
            SentenceChunker.chunk("Bring snacks, e.g. chips and dip. Everyone shares.").map { it.text },
        )
        assertEquals(
            listOf("Invite friends, etc. 12 seats left.", "Hurry."),
            SentenceChunker.chunk("Invite friends, etc. 12 seats left. Hurry.").map { it.text },
        )
    }

    @Test
    fun chunk_titlesBeforeCapitals_stayGlued() {
        // BreakIterator splits "Mr. Smith", but titles always glue so the voice never
        // speaks "Mr." as a separate fragment.
        assertEquals(
            listOf("Mr. Smith went home.", "He slept."),
            SentenceChunker.chunk("Mr. Smith went home. He slept.").map { it.text },
        )
    }

    @Test
    fun chunk_titlesWithTimesAndStreets_isOneChunk() {
        val input = "Mr. Smith met Dr. Jones at 5 p.m. on St. Mark's Rd."
        assertEquals(listOf(input), SentenceChunker.chunk(input).map { it.text })
    }

    @Test
    fun chunk_paragraphs_neverCrossBreaks() {
        val chunks = SentenceChunker.chunk("First para one. Two here.\n\nSecond para here. More words.")
        assertEquals(
            listOf("First para one. Two here.", "Second para here. More words."),
            chunks.map { it.text },
        )
        assertTrue(chunks.none { '\n' in it.text })
    }

    @Test
    fun chunk_fa_splitsAtQuestionMarkAndPeriod() {
        val first = "آیا این یک جمله فارسی طولانی برای آزمایش است؟"
        val second = "این جمله دوم است که کمی طولانی‌تر می‌باشد."
        val input = "$first $second"
        val chunks = SentenceChunker.chunk(input, "fa")
        assertEquals(listOf(first, second), chunks.map { it.text })
        for (chunk in chunks) {
            assertEquals(chunk.text, input.substring(chunk.start, chunk.end).trim())
        }
    }

    @Test
    fun chunk_fa_splitsAtSemicolon() {
        // First sentence is 22 chars, so the tiny-first merge (< 20) doesn't fire.
        val chunks = SentenceChunker.chunk("او دیروز به خانه آمد؛ نشست و استراحت کرد.", "fa")
        assertEquals(
            listOf("او دیروز به خانه آمد؛", "نشست و استراحت کرد."),
            chunks.map { it.text },
        )
    }

    @Test
    fun chunk_fa_longSentence_splitsAfterArabicComma() {
        val long = "ا".repeat(289) + "، ب" + "ب".repeat(50) + "."
        val input = "مقدمه. $long"
        val chunks = SentenceChunker.chunk(input, "fa")
        assertEquals(3, chunks.size)
        assertEquals("مقدمه.", chunks[0].text)
        assertTrue(chunks[1].text.endsWith("،"))
        assertTrue(chunks[1].text.length <= 300)
        assertEquals(long, chunks[1].text + " " + chunks[2].text)
        for (chunk in chunks) {
            assertEquals(chunk.text, input.substring(chunk.start, chunk.end).trim())
        }
    }

    @Test
    fun chunk_fa_firstChunk_cutsAfterArabicCommaInWindow() {
        val first = "ا".repeat(45) + "، " + "ب".repeat(92) + "."
        assertEquals(140, first.length)
        val chunks = SentenceChunker.chunk(first, "fa")
        assertEquals(2, chunks.size)
        assertEquals("ا".repeat(45) + "،", chunks[0].text)
        assertEquals(first, chunks[0].text + " " + chunks[1].text)
        for (chunk in chunks) {
            assertEquals(chunk.text, first.substring(chunk.start, chunk.end).trim())
        }
    }

    @Test
    fun chunk_fa_firstSentence_cutsOnlyAtClauseMark() {
        val input = "به گزارش خبرگزاری‌ها، سازمان هواشناسی اعلام کرد که از اواخر هفتهٔ جاری بارش باران و برف در بیشتر استان‌های شمالی و غربی کشور آغاز می‌شود و رانندگان باید در جاده‌های کوهستانی با احتیاط بیشتری رانندگی کنند."
        val chunks = SentenceChunker.chunk(input, "fa")
        assertEquals(2, chunks.size)
        assertEquals("به گزارش خبرگزاری‌ها،", chunks[0].text)
        for (chunk in chunks) {
            assertEquals(chunk.text, input.substring(chunk.start, chunk.end))
        }
    }

    @Test
    fun chunk_fa_firstSentenceWithoutClauseMark_staysWhole() {
        val first = "ا".repeat(141) + "."
        assertEquals(142, first.length)
        val chunks = SentenceChunker.chunk(first, "fa")
        assertEquals(1, chunks.size)
        assertEquals(first, chunks[0].text)
        assertEquals(chunks[0].text, first.substring(chunks[0].start, chunks[0].end))
    }

    @Test
    fun chunk_fa_firstSentenceMarkAtOffset3_isNotCutThere() {
        // Only clause mark ends at offset 3: too short to play alone.
        val input = "نه، " + "ا".repeat(100) + "."
        val chunks = SentenceChunker.chunk(input, "fa")
        assertEquals(1, chunks.size)
        assertEquals(input, chunks[0].text)
    }

    @Test
    fun chunk_en_firstSentenceWithTime_isNotCutInsideTime() {
        val first = "a".repeat(30) + " 8:30 " + "b".repeat(60) + "."
        val chunks = SentenceChunker.chunk(first, "en")
        assertEquals(2, chunks.size)
        assertEquals("a".repeat(30) + " 8:30", chunks[0].text)
        assertTrue(!chunks[0].text.endsWith(":"))
        for (chunk in chunks) {
            assertEquals(chunk.text, first.substring(chunk.start, chunk.end).trim())
        }
    }

    @Test
    fun chunk_fa_firstSentenceWithTime_isNotCutInsideTime() {
        val input = "ا".repeat(30) + " ۸:۳۰ " + "ب".repeat(60) + "."
        val chunks = SentenceChunker.chunk(input, "fa")
        assertEquals(1, chunks.size)
        assertEquals(input, chunks[0].text)
    }

    @Test
    fun chunk_longSentence_isNotSplitInsideGroupedNumber() {
        val long = "A" + "a".repeat(289) + " 1,250 " + "b".repeat(50) + "."
        val chunks = SentenceChunker.chunk("Intro here. $long")
        assertEquals(3, chunks.size)
        assertEquals("Intro here.", chunks[0].text)
        assertTrue(chunks[1].text.endsWith("1,250"))
        assertEquals("b".repeat(50) + ".", chunks[2].text)
        assertEquals(long, chunks[1].text + " " + chunks[2].text)
    }

    @Test
    fun chunk_en_firstSentenceWithoutClauseMark_stillCutsAsToday() {
        // Same shape as the Persian no-mark sentence: English still cuts.
        val first = "a".repeat(141) + "."
        assertEquals(142, first.length)
        val chunks = SentenceChunker.chunk(first, "en")
        assertEquals(2, chunks.size)
        assertEquals(60, chunks[0].text.length)
        for (chunk in chunks) {
            assertEquals(chunk.text, first.substring(chunk.start, chunk.end).trim())
        }
        // Same shape as the Persian clause-mark sentence: an early comma
        // does not hold English to a clause-only cut, so the space
        // fallback before char 60 still applies.
        val withComma = "Report from agencies, " + "word ".repeat(30).trim() + "."
        val commaChunks = SentenceChunker.chunk(withComma, "en")
        assertTrue(commaChunks.size >= 2)
        assertTrue(commaChunks[0].text.length <= 60)
        for (chunk in commaChunks) {
            assertEquals(chunk.text, withComma.substring(chunk.start, chunk.end).trim())
        }
    }

    @Test
    fun chunk_tinyFirstSentence_mergesForwardWithin80Chars() {
        val first = "سلام!"
        val second = "این یک آزمایش است."
        val third = "این یک جمله سوم بسیار طولانی است که باید در قطعه دوم قرار گیرد."
        val input = "$first $second $third"
        val chunks = SentenceChunker.chunk(input, "fa")
        // The next sentence would exceed 80, so it is cut: the first chunk holds
        // the first two sentences plus a word-boundary cut of the third.
        assertEquals(2, chunks.size)
        assertTrue(chunks[0].text.startsWith("$first $second"))
        assertTrue(chunks[0].text.length <= 80)
        for (chunk in chunks) {
            assertEquals(chunk.text, input.substring(chunk.start, chunk.end).trim())
        }
    }

    @Test
    fun chunk_tinyFirstSentence_cutsNextWhenItExceeds80() {
        val long = "word ".repeat(40).trim() + "."
        val input = "Hi. $long"
        val chunks = SentenceChunker.chunk(input)
        assertTrue(chunks[0].text.startsWith("Hi. "))
        assertTrue(chunks[0].text.length <= 60)
        for (chunk in chunks) {
            assertEquals(chunk.text, input.substring(chunk.start, chunk.end).trim())
        }
    }

    @Test
    fun chunk_abbreviationGlue_appliesOnlyToEnglish() {
        // Uppercase Z: BreakIterator splits after "Mr." in both locales (it never
        // splits before lowercase), so only the glue differs. Too long to re-merge.
        val long = "Z".repeat(250) + "."
        val input = "Intro here.\n\nMr. $long"
        assertEquals(
            listOf("Intro here.", "Mr. $long"),
            SentenceChunker.chunk(input, "en").map { it.text },
        )
        assertEquals(
            listOf("Intro here.", "Mr.", long),
            SentenceChunker.chunk(input, "fa").map { it.text },
        )
    }
}
