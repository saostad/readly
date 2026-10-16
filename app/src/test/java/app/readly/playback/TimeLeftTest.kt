package app.readly.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeLeftTest {

    @Test
    fun hiddenUntilTwoChunksPlayed() {
        assertNull(TimeLeft.minutesLeft(0, 6000, 100.0))
        assertNull(TimeLeft.minutesLeft(1, 6000, 100.0))
        assertEquals(10, TimeLeft.minutesLeft(2, 6000, 100.0))
    }

    @Test
    fun hiddenWithoutMeasurement() {
        assertNull(TimeLeft.minutesLeft(3, 0, 100.0))
        assertNull(TimeLeft.minutesLeft(3, 6000, 0.0))
        assertNull(TimeLeft.minutesLeft(3, -5, 100.0))
    }

    @Test
    fun remainingCharsTimesMsPerChar() {
        // 6000 chars × 100 ms = 600 s = 10 min.
        assertEquals(10, TimeLeft.minutesLeft(5, 6000, 100.0))
        // 900 chars × 200 ms = 180 s = 3 min.
        assertEquals(3, TimeLeft.minutesLeft(2, 900, 200.0))
    }

    @Test
    fun tinyRemainderShowsOneMinute() {
        assertEquals(1, TimeLeft.minutesLeft(4, 30, 100.0))
    }
}
