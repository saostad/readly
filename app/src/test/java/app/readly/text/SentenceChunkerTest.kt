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
        val chunks = SentenceChunker.chunk("Hi. OK. This is a longer sentence here.")
        assertEquals(
            listOf("Hi.", "OK. This is a longer sentence here."),
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
        val long = "Word " + "word ".repeat(79) + "tail."
        assertTrue(long.length > 300)
        val chunks = SentenceChunker.chunk("Intro here. $long")
        assertEquals(3, chunks.size)
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
            listOf("First para one.", "Two here.", "Second para here. More words."),
            chunks.map { it.text },
        )
        assertTrue(chunks.none { '\n' in it.text })
    }
}
