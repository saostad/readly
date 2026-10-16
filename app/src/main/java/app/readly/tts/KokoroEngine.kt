package app.readly.tts

import android.os.SystemClock
import android.util.Log
import app.readly.model.VoiceModelStore
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.Closeable
import java.io.IOException
import java.util.Locale

/**
 * Kokoro on-device speech engine, backed by sherpa-onnx native code.
 *
 * Not thread-safe: every call (including [close]) must come from one owner thread.
 * Round 3's playback service will own it.
 *
 * @throws IllegalStateException if the voice model [is not installed][VoiceModelStore.isInstalled].
 * @throws IOException if the native voice fails to load, naming the dir and the real error.
 */
class KokoroEngine(store: VoiceModelStore) : Closeable {

    companion object {
        /** Voice code to display name. The speaker id passed to [synthesize] is the index. */
        val SPEAKERS = listOf(
            "af" to "Default (American, female)",
            "af_bella" to "Bella (American, female)",
            "af_nicole" to "Nicole (American, female)",
            "af_sarah" to "Sarah (American, female)",
            "af_sky" to "Sky (American, female)",
            "am_adam" to "Adam (American, male)",
            "am_michael" to "Michael (American, male)",
            "bf_emma" to "Emma (British, female)",
            "bf_isabella" to "Isabella (British, female)",
            "bm_george" to "George (British, male)",
            "bm_lewis" to "Lewis (British, male)",
        )
    }

    private val dir = store.modelDir.absolutePath
    private val tts: OfflineTts

    init {
        if (!store.isInstalled()) throw IllegalStateException("Voice model is not installed")
        val startMs = SystemClock.elapsedRealtime()
        tts = try {
            OfflineTts(null, buildConfig(dir))
        } catch (e: Exception) {
            throw IOException(loadError(dir, e), e)
        } catch (e: LinkageError) {
            // Missing native libs surface as UnsatisfiedLinkError, not Exception.
            throw IOException(loadError(dir, e), e)
        }
        Log.d("Readly", "Kokoro voice loaded from $dir in ${SystemClock.elapsedRealtime() - startMs} ms")
    }

    /** Sample rate of [synthesize] output. */
    val sampleRate: Int
        get() = tts.sampleRate()

    /**
     * Synthesizes [text] to mono float PCM. Generation stops early (returning audio so far)
     * once [isStopped] returns true.
     */
    fun synthesize(text: String, speakerId: Int, speed: Float, isStopped: () -> Boolean): FloatArray {
        require(speakerId in SPEAKERS.indices) {
            "Unknown speaker id $speakerId: Kokoro has ${SPEAKERS.size} speakers " +
                "(0..${SPEAKERS.size - 1})"
        }
        if (text.isBlank()) return FloatArray(0)
        val startMs = SystemClock.elapsedRealtime()
        val audio = tts.generateWithCallback(text, speakerId, speed) { if (isStopped()) 0 else 1 }
        val synthMs = SystemClock.elapsedRealtime() - startMs
        val audioMs = if (audio.sampleRate > 0) audio.samples.size * 1000.0 / audio.sampleRate else 0.0
        val rtf = if (audioMs > 0) synthMs / audioMs else 0.0
        Log.d(
            "Readly",
            "synthesize: chars=${text.length} speaker=$speakerId speed=$speed " +
                "synthMs=$synthMs audioMs=${audioMs.toLong()} " +
                "rtf=${String.format(Locale.US, "%.3f", rtf)}",
        )
        return audio.samples
    }

    override fun close() {
        tts.release()
    }

    private fun buildConfig(dir: String): OfflineTtsConfig {
        val kokoro = OfflineTtsKokoroModelConfig(
            model = "$dir/model.int8.onnx",
            voices = "$dir/voices.bin",
            tokens = "$dir/tokens.txt",
            dataDir = "$dir/espeak-ng-data",
        )
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        val model = OfflineTtsModelConfig(kokoro = kokoro, numThreads = threads, debug = false, provider = "cpu")
        return OfflineTtsConfig(model = model, maxNumSentences = 1)
    }

    private fun loadError(dir: String, e: Throwable): String =
        "Could not load the Kokoro voice from $dir: ${e.javaClass.name}: ${e.message}"
}
