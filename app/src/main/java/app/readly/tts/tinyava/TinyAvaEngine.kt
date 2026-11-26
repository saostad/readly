package app.readly.tts.tinyava

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.os.SystemClock
import android.util.Log
import app.readly.text.LatinFa
import app.readly.tts.pocket.G2p
import app.readly.tts.pocket.PocketAudio
import app.readly.tts.pocket.PocketTextV2
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs

/**
 * Tiny Ava (Parisa) Persian TTS engine on ONNX Runtime.
 *
 * Runs `tiny-ava-parisa.onnx` (~21.4 MB, 24 kHz mono) using Readly's embedded
 * `Homo-GE2PE` Persian [G2p] and gentle mastering ([PocketAudio.finishSpeechGentle]).
 *
 * Not thread-safe: every call (including [close]) must come from one owner thread.
 */
class TinyAvaEngine(
    modelFile: File,
    g2pProvider: (() -> G2p)? = null,
) : Closeable {

    private val env: OrtEnvironment
    private val session: OrtSession
    private val g2p: G2p

    /** Output sample rate in Hz (24000). */
    val sampleRate: Int = TinyAvaPhonemes.SAMPLE_RATE

    init {
        if (!modelFile.exists()) {
            throw IOException("Tiny Ava voice file is missing: ${modelFile.path}")
        }
        val path = modelFile.path
        env = OrtEnvironment.getEnvironment()
        val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 4)
        session = try {
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(threads)
                options.setInterOpNumThreads(1)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                env.createSession(path, options)
            }
        } catch (e: OrtException) {
            throw IOException("Couldn't load the Tiny Ava voice from $path: ${e.message}", e)
        }
        g2p = try {
            (g2pProvider ?: throw IOException(
                "Couldn't load the Tiny Ava voice from $path: no pronunciation model was provided",
            ))()
        } catch (e: Exception) {
            try {
                session.close()
            } catch (closeError: OrtException) {
                e.addSuppressed(closeError)
            }
            if (e is IOException) throw e
            throw IOException("Couldn't load the Tiny Ava pronunciation model: ${e.message}", e)
        }
    }

    /**
     * Synthesizes [text] to mono float PCM at [sampleRate]. Generation stops early
     * once [isStopped] returns true. [onChunk] streams each mastered piece and its
     * trailing pause.
     */
    fun synthesize(
        text: String,
        speed: Float,
        isStopped: () -> Boolean,
        onChunk: ((FloatArray) -> Unit)? = null,
    ): FloatArray {
        val clean = text.replace("\u0000", "")
        val startMs = SystemClock.elapsedRealtime()
        val normalized = try {
            PocketTextV2.normalizeForModel(clean)
        } catch (e: IllegalArgumentException) {
            Log.i("Readly", "TinyAva: nothing speakable in ${clean.take(40)}")
            return FloatArray(0)
        }
        val sentences = PocketTextV2.splitSentences(normalized)
        if (sentences.isEmpty()) {
            Log.i("Readly", "TinyAva: nothing speakable in ${clean.take(40)}")
            return FloatArray(0)
        }

        val (neuralSpeed, resampleSpeed) = TinyAvaPhonemes.decomposeSpeed(speed)
        var g2pMs = 0L
        val planned = ArrayList<Pair<TinyAvaPhonemes.TokenSequence, PocketAudio.PieceEnd>>()
        val lastSent = sentences.lastIndex

        for ((sentIdx, sentence) in sentences.withIndex()) {
            if (isStopped()) break
            val sentEnd = if (sentIdx == lastSent) {
                PocketAudio.classifyPieceEnd(normalized)
            } else {
                PocketAudio.PieceEnd.SENTENCE
            }
            for (piece in TinyAvaPhonemes.splitLongSentence(sentence, sentEnd)) {
                if (isStopped()) break
                val t0 = SystemClock.elapsedRealtime()
                val items = pronouncableItems(piece.text, isStopped)
                g2pMs += SystemClock.elapsedRealtime() - t0
                planned.addAll(TinyAvaPhonemes.splitItemsToBudget(items, piece.end))
            }
        }

        if (planned.isEmpty() || isStopped()) {
            return FloatArray(0)
        }

        val finished = PocketAudio.finishSpeechGentle(
            sequence {
                for ((seq, end) in planned) {
                    if (isStopped()) break
                    val wav = runModel(seq.tokenIds, seq.stressIds, neuralSpeed)
                    if (wav.isNotEmpty()) {
                        yield(PocketAudio.RawPiece(wav, end))
                    }
                }
            },
            sampleRate,
            resampleSpeed,
            isStopped,
            onChunk,
        ) { SystemClock.elapsedRealtime() }

        val audio = finished.audio
        val synthMs = SystemClock.elapsedRealtime() - startMs
        val audioMs = audio.size * 1000.0 / sampleRate
        val rtf = if (audioMs > 0) synthMs / audioMs else 0.0
        Log.i(
            "Readly",
            "synthesize: chars=${text.length} speed=$speed " +
                "synthMs=$synthMs g2pMs=$g2pMs masterMs=${finished.masterMs} " +
                "audioMs=${audioMs.toLong()} rtf=${String.format(Locale.US, "%.3f", rtf)}",
        )
        return audio
    }

    private fun pronounceUnalignedWindow(
        window: String,
        items: MutableList<TinyAvaPhonemes.TokenItem>,
        stressState: TinyAvaPhonemes.StressState,
        isStopped: () -> Boolean,
    ) {
        for ((isPunct, piece) in TinyAvaPhonemes.splitPunctChunks(window)) {
            if (isStopped()) break
            if (isPunct) {
                val id = TinyAvaPhonemes.SYMBOLS.indexOf(piece).toLong()
                if (id >= 0) {
                    stressState.onPunct()
                    items.add(TinyAvaPhonemes.TokenItem.Punct(id))
                }
                continue
            }
            val cleaned = PocketTextV2.cleanSentenceForG2p(piece)
            if (cleaned.isBlank()) continue
            val phonemes = g2p.phonemise(cleaned)
            val translated = PocketTextV2.translatePhonemes(phonemes)
            val fa = PocketTextV2.faWords(piece)
            val windowPh = PocketTextV2.dedupeRepeats(
                fa,
                translated.trim().split(Regex("\\s+")).filter { it.isNotEmpty() },
            )
            val counts = PocketTextV2.alignWords(fa, windowPh)
            val finalPh = if (counts != null) {
                PocketTextV2.applyOverrides(
                    fa,
                    windowPh,
                    counts,
                ) { word -> LatinFa.phonemeOverrides()[word] }.phonemeWords
            } else {
                windowPh
            }
            for (word in finalPh) {
                val phones = TinyAvaPhonemes.ge2peWordToIpaInContext(word, stressState)
                if (phones.isNotEmpty()) {
                    items.add(TinyAvaPhonemes.TokenItem.Word(phones))
                }
            }
        }
    }

    private fun pronouncableItems(
        text: String,
        isStopped: () -> Boolean,
    ): List<TinyAvaPhonemes.TokenItem> {
        val withMark = TinyAvaPhonemes.ensureTerminalMark(text)
        val items = ArrayList<TinyAvaPhonemes.TokenItem>()
        val stressState = TinyAvaPhonemes.StressState()
        for (window in PocketTextV2.g2pWindows(withMark, TinyAvaPhonemes.G2P_WINDOW_CHARS)) {
            if (isStopped()) break
            val cleaned = PocketTextV2.cleanSentenceForG2p(window)
            if (cleaned.isBlank()) {
                for ((isPunct, piece) in TinyAvaPhonemes.splitPunctChunks(window)) {
                    if (isPunct) {
                        val id = TinyAvaPhonemes.SYMBOLS.indexOf(piece).toLong()
                        if (id >= 0) {
                            stressState.onPunct()
                            items.add(TinyAvaPhonemes.TokenItem.Punct(id))
                        }
                    }
                }
                continue
            }
            val phonemes = g2p.phonemise(cleaned)
            val translated = PocketTextV2.translatePhonemes(phonemes)
            val fa = PocketTextV2.faWords(window)
            val windowPh = PocketTextV2.dedupeRepeats(
                fa,
                translated.trim().split(Regex("\\s+")).filter { it.isNotEmpty() },
            )
            val counts = PocketTextV2.alignWords(fa, windowPh)
            if (counts != null) {
                val applied = PocketTextV2.applyOverrides(
                    fa,
                    windowPh,
                    counts,
                ) { word -> LatinFa.phonemeOverrides()[word] }
                items.addAll(
                    TinyAvaPhonemes.attachWindowItems(
                        window,
                        applied.phonemeWords,
                        applied.counts,
                        stressState,
                    ),
                )
            } else {
                pronounceUnalignedWindow(window, items, stressState, isStopped)
            }
        }
        return items
    }

    private fun runModel(tokenIds: LongArray, stressIds: LongArray, neuralSpeed: Float): FloatArray {
        val n = tokenIds.size.toLong()
        val inputs = LinkedHashMap<String, OnnxTensor>(6)
        return try {
            inputs["tokens"] = longTensor(env, tokenIds, longArrayOf(1L, n))
            inputs["stress"] = longTensor(env, stressIds, longArrayOf(1L, n))
            inputs["voice"] = longTensor(env, longArrayOf(0L), longArrayOf(1L))
            inputs["speed"] = floatTensor(env, floatArrayOf(neuralSpeed), longArrayOf(1L))
            inputs["pitch_shift"] = floatTensor(env, floatArrayOf(TinyAvaPhonemes.PITCH_SHIFT), longArrayOf(1L))
            inputs["pitch_range"] = floatTensor(env, floatArrayOf(TinyAvaPhonemes.PITCH_RANGE), longArrayOf(1L))
            session.run(inputs).use { out ->
                val audioTensor = out.get("audio").get() as OnnxTensor
                val fb = audioTensor.floatBuffer
                val wav = FloatArray(fb.remaining())
                fb.get(wav)
                limitPeak(wav)
            }
        } catch (e: OrtException) {
            throw IOException("Tiny Ava synthesis failed: ${e.message}", e)
        } finally {
            for (tensor in inputs.values) {
                tensor.close()
            }
        }
    }

    override fun close() {
        try {
            session.close()
        } catch (e: OrtException) {
            throw IOException("Couldn't close the Tiny Ava voice: ${e.message}", e)
        } finally {
            g2p.close()
        }
    }

    private companion object {
        fun limitPeak(wave: FloatArray): FloatArray {
            var maxAbs = 0f
            for (x in wave) {
                val a = abs(x)
                if (a > maxAbs) maxAbs = a
            }
            if (maxAbs <= TinyAvaPhonemes.PEAK_LIMIT || maxAbs == 0f) return wave
            val scale = TinyAvaPhonemes.PEAK_LIMIT / maxAbs
            return FloatArray(wave.size) { wave[it] * scale }
        }

        fun floatTensor(env: OrtEnvironment, data: FloatArray, shape: LongArray): OnnxTensor {
            val buffer = ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            buffer.put(data)
            buffer.rewind()
            return OnnxTensor.createTensor(env, buffer, shape)
        }

        fun longTensor(env: OrtEnvironment, data: LongArray, shape: LongArray): OnnxTensor {
            val buffer = ByteBuffer.allocateDirect(data.size * 8)
                .order(ByteOrder.nativeOrder()).asLongBuffer()
            buffer.put(data)
            buffer.rewind()
            return OnnxTensor.createTensor(env, buffer, shape)
        }
    }
}
