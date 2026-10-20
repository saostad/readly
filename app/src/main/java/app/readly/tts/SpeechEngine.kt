package app.readly.tts

import android.os.SystemClock
import android.util.Log
import app.readly.model.VoiceFamily
import app.readly.model.VoiceModelStore
import app.readly.tts.pocket.G2p
import app.readly.tts.pocket.PocketEngine
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.Closeable
import java.io.File
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
class SpeechEngine(store: VoiceModelStore, g2pProvider: (() -> G2p)? = null) : Closeable {

    private val pack = store.pack
    private val dir = store.modelDir.absolutePath
    private val tts: OfflineTts?
    private val pocket: PocketEngine?

    init {
        if (!store.isInstalled()) throw IllegalStateException("Voice model is not installed")
        val startMs = SystemClock.elapsedRealtime()
        if (pack.family == VoiceFamily.POCKET) {
            pocket = try {
                PocketEngine(File(store.modelDir, pack.modelFile), g2pProvider)
            } catch (e: Exception) {
                throw IOException(loadError(dir, e), e)
            } catch (e: LinkageError) {
                // A missing libonnxruntime4j_jni.so surfaces as UnsatisfiedLinkError,
                // not Exception; loadError names the missing library.
                throw IOException(loadError(dir, e), e)
            }
            tts = null
        } else {
            tts = try {
                OfflineTts(null, buildConfig(dir))
            } catch (e: Exception) {
                throw IOException(loadError(dir, e), e)
            } catch (e: LinkageError) {
                // Missing native libs surface as UnsatisfiedLinkError, not Exception.
                throw IOException(loadError(dir, e), e)
            }
            pocket = null
        }
        Log.i("Readly", "${pack.displayName} voice loaded from $dir in ${SystemClock.elapsedRealtime() - startMs} ms")
    }

    /** Sample rate of [synthesize] output. */
    val sampleRate: Int
        get() = pocket?.sampleRate ?: tts!!.sampleRate()

    /**
     * Synthesizes [text] to mono float PCM. Generation stops early (returning audio so far)
     * once [isStopped] returns true. [lang] is the 2-letter document language, used
     * only by Supertonic 3 (`extra["lang"]`); Supertonic 2 stays `"en"`. [onChunk]
     * streams partial audio from sherpa's callback (for the system TTS service).
     */
    fun synthesize(
        text: String,
        speakerId: Int,
        speed: Float,
        isStopped: () -> Boolean,
        lang: String = "en",
        onChunk: ((FloatArray) -> Unit)? = null,
    ): FloatArray {
        val speakers = pack.speakers
        require(speakerId in speakers.indices) {
            "Unknown speaker id $speakerId: ${pack.displayName} has ${speakers.size} speakers " +
                "(0..${speakers.size - 1})"
        }
        if (text.isBlank()) return FloatArray(0)
        // Pocket ignores lang (Persian only) and logs its own synthesize line.
        pocket?.let { return it.synthesize(text, speed, isStopped, onChunk) }
        val sherpa = tts ?: error("no speech engine is loaded")
        val startMs = SystemClock.elapsedRealtime()
        val callback: (FloatArray) -> Int =
            if (onChunk != null) StreamingCallback(isStopped, onChunk) else StopCallback(isStopped)
        val audio = if (pack.family == VoiceFamily.SUPERTONIC) {
            // sherpa's own Supertonic example passes the language this way; numSteps
            // stays at its default. Only v3 is multilingual; v2 stays "en".
            val supertonicLang = if (pack.id == "supertonic-3") lang else "en"
            val config = GenerationConfig(
                sid = speakerId,
                speed = speed,
                extra = mapOf("lang" to supertonicLang),
            )
            sherpa.generateWithConfigAndCallback(text, config, callback)
        } else {
            sherpa.generateWithCallback(text, speakerId, speed, callback)
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

    /**
     * Starts pronouncing [text] ahead on the prefetch thread, so the next
     * chunk's synthesis can reuse it. Thread-safe (may be called from any
     * thread); no-op unless this is a Pocket v2 voice. Never throws.
     */
    fun prefetch(text: String) {
        try {
            pocket?.prefetch(text)
        } catch (_: Exception) {
            // Prefetch never fails synthesis.
        }
    }

    /** Cancels any prefetched work. Thread-safe; never throws. */
    fun cancelPrefetch() {
        try {
            pocket?.cancelPrefetch()
        } catch (_: Exception) {
            // Prefetch never fails synthesis.
        }
    }

    override fun close() {
        if (pocket != null) pocket.close() else tts?.release()
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
            VoiceFamily.POCKET -> error("Pocket is not a sherpa-onnx voice")
            VoiceFamily.PHONON -> error("Phonon is not a sherpa-onnx voice")
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

/**
 * Streaming JNI callback for [OfflineTts] synthesis. The same named-class rule as
 * [StopCallback] applies (sherpa-onnx looks up `invoke([F)Ljava/lang/Integer;` by
 * name, so this must never become a lambda). Forwards every partial chunk to
 * [onChunk] and stops generation once [isStopped] returns true.
 */
internal class StreamingCallback(
    private val isStopped: () -> Boolean,
    private val onChunk: (FloatArray) -> Unit,
) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int {
        onChunk(samples)
        return if (isStopped()) 0 else 1
    }
}
