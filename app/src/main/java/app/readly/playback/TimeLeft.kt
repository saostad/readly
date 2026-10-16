package app.readly.playback

import kotlin.math.roundToInt

/**
 * The Now-playing "~X min left" estimate: remaining chars × the average
 * audio-ms per char measured so far in this document. Pure so it is
 * JVM-testable; the service publishes the inputs on [ReaderSnapshot].
 */
object TimeLeft {

    /**
     * Minutes left, or null when the estimate must stay hidden (fewer than 2
     * chunks played, or no measurement yet).
     */
    fun minutesLeft(playedChunks: Int, remainingChars: Int, msPerChar: Double): Int? {
        if (playedChunks < 2 || remainingChars <= 0 || msPerChar <= 0.0) return null
        return ((remainingChars * msPerChar) / 60_000).roundToInt().coerceAtLeast(1)
    }
}
