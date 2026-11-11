package app.readly.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class SiteArtworkFitTest {

    // A fake font: every character is 0.6 × the size wide.
    private val measure: (String, Float) -> Float = { text, size -> text.length * size * 0.6f }

    @Test
    fun longestWordFitsOnOneLine() {
        // "Technology" (10 chars) at 46 px is 276 px; it must shrink to fit 208 px.
        val size = ArtworkFit.textSize("Forbes Technology", 46f, 22f, 3f, 208f, measure)
        assertEquals(34f, size)
        assert(measure("Technology", size) <= 208f)
    }

    @Test
    fun shortNamesKeepTheLargestSize() {
        assertEquals(46f, ArtworkFit.textSize("The Verge", 46f, 22f, 3f, 208f, measure))
    }

    @Test
    fun hugeWordsStopAtTheMinimum() {
        assertEquals(22f, ArtworkFit.textSize("Supercalifragilisticexpialidocious", 46f, 22f, 3f, 208f, measure))
    }
}
