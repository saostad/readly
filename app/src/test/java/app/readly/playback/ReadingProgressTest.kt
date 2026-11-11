package app.readly.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingProgressTest {

    @Test
    fun estimate_needsAMeasuredSentence() {
        assertNull(ReadingProgress.estimate(playedChunks = 0, totalChars = 100, remainingChars = 100, msPerChar = 60.0))
        assertNull(ReadingProgress.estimate(playedChunks = 1, totalChars = 100, remainingChars = 100, msPerChar = 0.0))
        assertNull(ReadingProgress.estimate(playedChunks = 1, totalChars = 0, remainingChars = 0, msPerChar = 60.0))
    }

    @Test
    fun estimate_positionIsTheCurrentSentenceStart() {
        // 1,000 chars at 60 ms each: 60 s long; 250 chars read → 15 s in.
        val e = ReadingProgress.estimate(playedChunks = 3, totalChars = 1_000, remainingChars = 750, msPerChar = 60.0)!!
        assertEquals(15_000L, e.positionMs)
        assertEquals(60_000L, e.durationMs)
    }

    @Test
    fun indexAt_findsTheSentenceAtAPositionAndClamps() {
        val lengths = listOf(100, 50, 200)
        assertEquals(0, ReadingProgress.indexAt(lengths, 0, 10.0))
        assertEquals(0, ReadingProgress.indexAt(lengths, 999, 10.0))
        assertEquals(1, ReadingProgress.indexAt(lengths, 1_000, 10.0))
        assertEquals(2, ReadingProgress.indexAt(lengths, 1_500, 10.0))
        assertEquals(2, ReadingProgress.indexAt(lengths, 999_999, 10.0))
        assertEquals(0, ReadingProgress.indexAt(emptyList(), 5_000, 10.0))
    }

    @Test
    fun clock_readsLikeAPlayer() {
        assertEquals("0:00", ReadingProgress.clock(0))
        assertEquals("0:09", ReadingProgress.clock(9_400))
        assertEquals("12:05", ReadingProgress.clock(725_000))
        assertEquals("1:02:03", ReadingProgress.clock(3_723_000))
    }
}
