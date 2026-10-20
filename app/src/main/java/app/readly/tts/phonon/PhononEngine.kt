package app.readly.tts.phonon

import android.os.SystemClock
import android.system.Os
import android.util.Log
import app.readly.model.VoiceCatalog
import app.readly.tts.pocket.PocketAudio
import java.io.Closeable
import java.io.File
import java.util.Locale

/**
 * Phonon English synthesis over `libptts_ffi.so`, through [PhononTts].
 *
 * Not thread-safe: every call (including [close]) must come from one owner thread.
 * The playback service owns it on `readly-tts` via
 * [SpeechEngine][app.readly.tts.SpeechEngine].
 *
 * @throws IllegalStateException when Phonon itself refuses to load the model; the message
 *   is Phonon's own error.
 */
internal class PhononEngine(modelDir: File) : Closeable {

    private val tts: PhononTts

    /** Voice currently set on [tts]; `ptts_set_voice` is skipped while it stays the same. */
    private var voice: String? = null

    /** Output sample rate in Hz (24 kHz mono float). */
    val sampleRate = 24_000

    init {
        // Pixel 11 Pro, same 17.8 s text: 2 threads RTF 0.32, 3 → 0.30–0.32,
        // 4 → 0.24, 6 → 0.24; 4 is the smallest count at the best speed. Must be
        // set before the first ptts_new: rayon reads it once.
        if (!rayonThreadsSet) {
            Os.setenv("RAYON_NUM_THREADS", "4", false)
            rayonThreadsSet = true
        }
        // A failed load throws IllegalStateException with Phonon's own error.
        tts = PhononTts(modelDir.absolutePath, "en")
    }

    /**
     * Synthesizes [text] to mono float PCM at [sampleRate]. [speakerId] indexes
     * [VoiceCatalog.PHONON_VOICE_FILES]. [speed] is clamped to 0.5..2.0 (non-finite
     * becomes 1.0) and applied per streamed piece. Generation stops early (returning
     * the audio made so far, no error) once [isStopped] returns true. [onChunk] gets
     * every speed-adjusted piece, exactly what the returned array contains.
     */
    fun synthesize(
        text: String,
        speakerId: Int,
        speed: Float,
        isStopped: () -> Boolean,
        onChunk: ((FloatArray) -> Unit)? = null,
    ): FloatArray {
        val voiceName = voiceNameFor(speakerId)
        if (voiceName != voice) {
            tts.setVoice(voiceName)
            voice = voiceName
        }
        val clampedSpeed = clampSpeed(speed)
        val pieces = ArrayList<FloatArray>()
        val startMs = SystemClock.elapsedRealtime()
        val result = tts.speak(text) { pcm ->
            if (isStopped()) {
                false
            } else {
                val audio = PocketAudio.applySpeed(pcm, clampedSpeed)
                onChunk?.invoke(audio)
                pieces += audio
                true
            }
        }
        val audio = concat(pieces)
        val synthMs = SystemClock.elapsedRealtime() - startMs
        val audioMs = audio.size * 1000.0 / sampleRate
        val rtf = if (audioMs > 0) synthMs / audioMs else 0.0
        Log.i(
            "Readly",
            "Phonon synthesize: chars=${text.length} voice=$voiceName speed=$speed " +
                "synthMs=$synthMs audioMs=${audioMs.toLong()} " +
                "ttfaMs=${result.ttfaMs.toLong()} " +
                "rtf=${String.format(Locale.US, "%.3f", rtf)}",
        )
        return audio
    }

    override fun close() {
        tts.close()
    }

    internal companion object {
        /** True once this process has set RAYON_NUM_THREADS, before any ptts_new. */
        @Volatile
        private var rayonThreadsSet = false

        /** Speed clamp shared with Pocket: 0.5..2.0, non-finite → 1.0. */
        internal fun clampSpeed(speed: Float): Float =
            if (speed.isFinite()) speed.coerceIn(0.5f, 2.0f) else 1.0f

        /** The voice file/name for [speakerId] in [VoiceCatalog.PHONON_VOICE_FILES]. */
        internal fun voiceNameFor(speakerId: Int): String {
            val voices = VoiceCatalog.PHONON_VOICE_FILES
            require(speakerId in voices.indices) {
                "Unknown Phonon speaker id $speakerId: expected " +
                    "0..${voices.size - 1}"
            }
            return voices[speakerId]
        }

        private fun concat(parts: List<FloatArray>): FloatArray {
            var total = 0
            for (part in parts) total += part.size
            val out = FloatArray(total)
            var offset = 0
            for (part in parts) {
                part.copyInto(out, offset)
                offset += part.size
            }
            return out
        }
    }
}
