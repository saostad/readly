package app.readly

import android.content.Context
import android.media.MediaPlayer
import androidx.annotation.RawRes

/**
 * Plays one voice sample clip at a time. Callers show [play]'s returned reason (or the
 * async one) with their own error formatting, and must call [release] in `onStop`.
 */
class SamplePlayer {
    private var player: MediaPlayer? = null

    /**
     * Stops any current clip, then plays [resId]. Returns null when playback started;
     * otherwise a short reason (no exception exists in that path). Async playback
     * failures go to [onAsyncError] instead. [onDone] runs on completion.
     */
    fun play(
        context: Context,
        @RawRes resId: Int,
        onDone: () -> Unit,
        onAsyncError: (String) -> Unit,
    ): String? {
        stop()
        return try {
            val mp = MediaPlayer.create(context, resId)
            if (mp == null) {
                "MediaPlayer.create returned null"
            } else {
                player = mp
                mp.setOnCompletionListener { stop(); onDone() }
                mp.setOnErrorListener { _, what, extra ->
                    stop()
                    onAsyncError("MediaPlayer error $what,$extra")
                    true
                }
                mp.start()
                null
            }
        } catch (e: Exception) {
            stop()
            "${e.javaClass.name}: ${e.message}"
        }
    }

    fun stop() {
        val mp = player
        player = null
        try {
            mp?.stop()
        } catch (_: Exception) {
            // Already completed or never started; release below is what matters.
        }
        try {
            mp?.release()
        } catch (_: Exception) {
            // Releasing a dead player; nothing to report to.
        }
    }

    fun release() = stop()
}
