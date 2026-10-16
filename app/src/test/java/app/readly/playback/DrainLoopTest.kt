package app.readly.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrainLoopTest {

    @Test
    fun drainTarget_addsFramesToUnsignedHead() {
        assertEquals(74_000L, DrainLoop.drainTarget(50_000L, 24_000L))
        assertEquals(24_000L, DrainLoop.drainTarget(0L, 24_000L))
    }

    @Test
    fun hangScenario_lateHeadMeasurementOvershootsPastWhatPlaybackReaches() {
        // Round 20: the preview measured the head AFTER the blocking write loop, when it
        // had already advanced, so the target overshot and the drain looped forever once
        // playback stopped. The head reached here is all the audio that was written.
        val startHead = 50_000L
        val frames = 24_000L
        val reachedHead = startHead + frames
        assertTrue(DrainLoop.isDrained(reachedHead, DrainLoop.drainTarget(startHead, frames), 0, 60_000))
        val lateHead = startHead + 20_000L // advanced during the writes
        val buggyTarget = DrainLoop.drainTarget(lateHead, frames)
        assertTrue(buggyTarget > reachedHead)
        assertFalse(DrainLoop.isDrained(reachedHead, buggyTarget, 0, 60_000))
    }

    @Test
    fun reachedHead_drainsBeforeDeadline() {
        assertTrue(DrainLoop.isDrained(1_000L, 1_000L, 0, 3_000))
        assertTrue(DrainLoop.isDrained(1_001L, 1_000L, 100, 3_000))
        assertFalse(DrainLoop.isDrained(999L, 1_000L, 100, 3_000))
    }

    @Test
    fun timeoutPath_elapsedPastDeadlineDrains() {
        assertFalse(DrainLoop.isDrained(0L, 1_000L, 2_999L, 3_000L))
        assertTrue(DrainLoop.isDrained(0L, 1_000L, 3_000L, 3_000L))
        assertTrue(DrainLoop.isDrained(0L, 1_000L, 60_000L, 3_000L))
    }

    @Test
    fun headWrap_comparesAcross32BitBoundary() {
        val startHead = 0xFFFFFF00L
        val frames = 0x200L
        val target = DrainLoop.drainTarget(startHead, frames)
        assertEquals(0x100000100L, target)
        // Still draining: 0xF0 frames played before the wrap.
        assertFalse(DrainLoop.isDrained(0xFFFFFFF0L, target, 0, 60_000))
        // Wrapped but short: 0x150 played of 0x200.
        assertFalse(DrainLoop.isDrained(0x50L, target, 0, 60_000))
        // Exactly drained across the wrap.
        assertTrue(DrainLoop.isDrained(0x100L, target, 0, 60_000))
        assertTrue(DrainLoop.isDrained(0x150L, target, 0, 60_000))
    }

    @Test
    fun unsignedMasking_handlesRawNegativeReads() {
        // A raw Int read of -1 arrives as Long -1; it is the counter at 0xFFFFFFFF.
        assertEquals(0xFFFFFFFFL + 5, DrainLoop.drainTarget(-1L, 5))
        assertTrue(DrainLoop.isDrained(-1L, 0xFFFFFFFFL, 0, 60_000))
        assertFalse(DrainLoop.isDrained(-1L, 0x100000000L, 0, 60_000))
    }
}
