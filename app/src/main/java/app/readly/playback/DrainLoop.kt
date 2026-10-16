package app.readly.playback

/**
 * Drain math for [AudioTrack][android.media.AudioTrack] playback waits. Pure so it is
 * JVM-testable; both the reader's sink and the speaker preview drain through it.
 *
 * The head ([AudioTrack.getPlaybackHeadPosition][android.media.AudioTrack.getPlaybackHeadPosition])
 * is an unsigned 32-bit frame counter: it wraps, and it keeps advancing while blocking
 * writes run. Round 20 fixed a hang where the preview computed its drain target from the
 * head *after* the write loop, overshooting by the frames played meanwhile; once playback
 * stopped, the head could never reach the target and the drain looped forever.
 */
object DrainLoop {

    /** Masks a head read down to the unsigned 32-bit counter the HAL maintains. */
    const val HEAD_MASK = 0xFFFFFFFFL

    private const val HEAD_RANGE = 0x100000000L
    private const val HALF_RANGE = 0x80000000L

    /**
     * Drain target: [frames] past the unsigned head [startHead]. Capture [startHead]
     * *before* writing: the head advances during blocking writes, so measuring it after
     * overshoots and the target may never be reached.
     */
    fun drainTarget(startHead: Long, frames: Long): Long =
        (startHead and HEAD_MASK) + frames

    /**
     * True when the unsigned head [head] has reached [target], or [elapsedMs] has passed
     * [deadlineMs] (the caller then logs the timeout, flushes and continues instead of
     * blocking forever). Wrap-aware: when the target sits more than half the 32-bit
     * range above the head, the head wrapped and is compared in the next epoch. A drain
     * never spans half the range (2^31 frames is ~13 h at 44.1 kHz), so at most one wrap
     * can separate the two.
     */
    fun isDrained(head: Long, target: Long, elapsedMs: Long, deadlineMs: Long): Boolean {
        if (elapsedMs >= deadlineMs) return true
        val h = head and HEAD_MASK
        val adjusted = if (target - h > HALF_RANGE) h + HEAD_RANGE else h
        return adjusted >= target
    }
}
