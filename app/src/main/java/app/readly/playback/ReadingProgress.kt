package app.readly.playback

/**
 * An audio-player style position and length for a reading, shared by the phone's
 * Now playing bar and the media session (Android Auto, the notification). Speech is
 * made sentence by sentence, so both are estimates: characters × the average
 * audio-ms per character measured so far in this document ([ReaderSnapshot.msPerChar]).
 * Pure so it is JVM-testable.
 */
object ReadingProgress {

    data class Estimate(val positionMs: Long, val durationMs: Long)

    /**
     * Where the current sentence starts and how long the whole reading is, or null
     * until a sentence has played and been measured.
     */
    fun estimate(playedChunks: Int, totalChars: Int, remainingChars: Int, msPerChar: Double): Estimate? {
        if (playedChunks < 1 || totalChars <= 0 || msPerChar <= 0.0) return null
        val done = (totalChars - remainingChars).coerceIn(0, totalChars)
        return Estimate((done * msPerChar).toLong(), (totalChars * msPerChar).toLong())
    }

    fun estimate(snap: ReaderSnapshot): Estimate? =
        estimate(snap.playedChunks, snap.totalChars, snap.remainingChars, snap.msPerChar)

    /** The sentence playing at [positionMs], for a seek (clamped to the reading). */
    fun indexAt(chunkLengths: List<Int>, positionMs: Long, msPerChar: Double): Int {
        if (chunkLengths.isEmpty()) return 0
        if (msPerChar <= 0.0 || positionMs <= 0) return 0
        val targetChar = positionMs / msPerChar
        var start = 0L
        for ((i, length) in chunkLengths.withIndex()) {
            if (targetChar < start + length) return i
            start += length
        }
        return chunkLengths.size - 1
    }

    /** `m:ss`, or `h:mm:ss` from an hour, like an audio player's clock. */
    fun clock(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0) + 500) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }
}
