package app.readly.tts

import android.os.SystemClock
import android.util.Log
import app.readly.model.VoiceFamily
import app.readly.model.VoiceModelStore
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.Closeable
import java.io.IOException
import java.util.Locale

/**
 * On-device speech engine for any [VoicePack][app.readly.model.VoicePack], backed by
 * sherpa-onnx native code.
 *
 * Not thread-safe: every call (including [close]) must come from one owner thread.
 * The playback service owns it on `readly-tts`.
 *
 * @throws IllegalStateException if the voice pack [is not installed][VoiceModelStore.isInstalled].
 * @throws IOException if the native voice fails to load, naming the dir and the real error.
 */
class SpeechEngine(store: VoiceModelStore) : Closeable {

    private val pack = store.pack
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
        Log.i("Readly", "${pack.displayName} voice loaded from $dir in ${SystemClock.elapsedRealtime() - startMs} ms")
    }

    /** Sample rate of [synthesize] output. */
    val sampleRate: Int
        get() = tts.sampleRate()

    /**
     * Synthesizes [text] to mono float PCM. Generation stops early (returning audio so far)
     * once [isStopped] returns true.
     */
    fun synthesize(text: String, speakerId: Int, speed: Float, isStopped: () -> Boolean): FloatArray {
        val speakers = pack.speakers
        require(speakerId in speakers.indices) {
            "Unknown speaker id $speakerId: ${pack.displayName} has ${speakers.size} speakers " +
                "(0..${speakers.size - 1})"
        }
        if (text.isBlank()) return FloatArray(0)
        val startMs = SystemClock.elapsedRealtime()
        val audio = if (pack.family == VoiceFamily.SUPERTONIC) {
            // sherpa's own Supertonic example passes the language this way; numSteps
            // stays at its default.
            val config = GenerationConfig(
                sid = speakerId,
                speed = speed,
                extra = mapOf("lang" to "en"),
            )
            tts.generateWithConfigAndCallback(text, config, StopCallback(isStopped))
        } else {
            tts.generateWithCallback(text, speakerId, speed, StopCallback(isStopped))
        }
        val synthMs = SystemClock.elapsedRealtime() - startMs
        val audioMs = if (audio.sampleRate > 0) audio.samples.size * 1000.0 / audio.sampleRate else 0.0
        val rtf = if (audioMs > 0) synthMs / audioMs else 0.0
        Log.i(
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
        // On-device benchmark (Pixel 11 Pro, fp32 kokoro-en-v0_19, same 14.8 s of
        // audio): 3 threads -> RTF 0.66, 6 -> 0.48, 7 -> 0.61. NNAPI gives no
        // gain over cpu (0.47 vs 0.48), so the provider stays cpu. Don't "fix"
        // this back to fewer threads: synthesis time is what delays first sound.
        val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6)
        val model = when (pack.family) {
            VoiceFamily.KOKORO -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = "$dir/${pack.modelFile}",
                    voices = "$dir/voices.bin",
                    tokens = "$dir/tokens.txt",
                    dataDir = "$dir/espeak-ng-data",
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu",
            )
            VoiceFamily.PIPER -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "$dir/${pack.modelFile}",
                    tokens = "$dir/tokens.txt",
                    dataDir = "$dir/espeak-ng-data",
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu",
            )
            VoiceFamily.SUPERTONIC -> OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = "$dir/duration_predictor.int8.onnx",
                    textEncoder = "$dir/text_encoder.int8.onnx",
                    vectorEstimator = "$dir/vector_estimator.int8.onnx",
                    vocoder = "$dir/vocoder.int8.onnx",
                    ttsJson = "$dir/tts.json",
                    unicodeIndexer = "$dir/unicode_indexer.bin",
                    voiceStyle = "$dir/voice.bin",
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu",
            )
            VoiceFamily.KITTEN -> OfflineTtsModelConfig(
                kitten = OfflineTtsKittenModelConfig(
                    model = "$dir/${pack.modelFile}",
                    voices = "$dir/voices.bin",
                    tokens = "$dir/tokens.txt",
                    dataDir = "$dir/espeak-ng-data",
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu",
            )
        }
        return OfflineTtsConfig(model = model, maxNumSentences = 1)
    }

    private fun loadError(dir: String, e: Throwable): String =
        "Could not load the ${pack.displayName} voice from $dir: ${e.javaClass.name}: ${e.message}"
}

/**
 * JNI callback for [OfflineTts] synthesis ([OfflineTts.generateWithCallback] and
 * [OfflineTts.generateWithConfigAndCallback]). This must stay a named class implementing
 * `(FloatArray) -> Int`, never a lambda: sherpa-onnx's JNI calls
 * `GetMethodID(cls, "invoke", "([F)Ljava/lang/Integer;")`, but Kotlin 2.x compiles lambdas with
 * invokedynamic and D8 then emits only the erased bridge `invoke(Object)Object`, which aborts
 * the app on first synthesis with:
 *
 * `JNI DETECTED ERROR IN APPLICATION: JNI NewFloatArray called with pending exception
 * java.lang.NoSuchMethodError: no non-static method
 * "Lapp/readly/tts/KokoroEngine$$ExternalSyntheticLambda0;.invoke([F)Ljava/lang/Integer;"`
 */
internal class StopCallback(private val isStopped: () -> Boolean) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = if (isStopped()) 0 else 1
}
