package app.readly.tts.pocket

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.Locale
import java.util.SplittableRandom
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONException
import org.json.JSONObject

/**
 * Pocket generation tuning: the defaults that used to be scattered
 * constants, in one place.
 */
data class PocketTuning(
    /** Highest token budget per chunk. */
    val maxTokens: Int = 18,
    /** Lowest token budget per chunk. */
    val minTokens: Int = 8,
    /** Characters per window when pronouncing a sentence with G2P. */
    val g2pWindowChars: Int = 120,
    /** Sampling temperature for generation. */
    val temperature: Float = 0.3f,
    /** EOS logit threshold above which generation ends. */
    val eosThreshold: Float = -2.0f,
    /** Frames to keep generating after EOS. */
    val framesAfterEos: Int = 0,
    /** Sampler decode steps; 0 uses the model's own `sampler_decode_steps`. */
    val decodeSteps: Int = 0,
) {
    /**
     * @throws IllegalArgumentException naming the `pocket.*` key of the
     *   first out-of-range field, with the same ranges as the reference.
     */
    fun validate() {
        require(maxTokens in 8..50) { "pocket.max_tokens must be between 8 and 50" }
        require(minTokens in 1..maxTokens) { "pocket.min_tokens must be between 1 and $maxTokens" }
        require(temperature.isFinite() && temperature in 0.0f..1.5f) {
            "pocket.temperature must be between 0 and 1.5"
        }
        require(decodeSteps in 0..4) { "pocket.decode_steps must be between 0 and 4" }
        require(eosThreshold.isFinite() && eosThreshold in -10.0f..10.0f) {
            "pocket.eos_threshold must be between -10 and 10"
        }
        require(framesAfterEos in 0..10) { "pocket.frames_after_eos must be between 0 and 10" }
        require(g2pWindowChars in 40..1000) {
            "pocket.g2p_window_chars must be between 40 and 1000"
        }
    }
}

/**
 * Pocket TTS (Farsi v1/v2) engine on ONNX Runtime: a port of the tested reference engine
 * (`.agents/handoff/ref/pocket.rs`) for the same model family.
 *
 * The exported `.onnx` is one streaming step: text, voice conditioning, or the previous
 * latent goes in; one 80 ms audio frame plus the updated caches come out. This class
 * drives that graph with the Farsi generation recipe: 18-token chunks, runaway
 * retry/split, boundary-dependent pauses (150 ms after a sentence, 60 ms after a
 * clause, none mid-sentence), silence trim, and a mastering chain to
 * about −10 LUFS, compressed.
 *
 * The front end comes from the model's metadata flags: v1 speaks normalized Persian
 * script (capitalized, terminal period); v2 speaks G2P phonemes (no capitalisation,
 * terminal punctuation or padding) and needs [g2pProvider] to pronounce them.
 *
 * Runtime sharing: only the ORT Java API ships with readly (the java-only AAR); the
 * native `libonnxruntime.so` is sherpa-onnx's — one ONNX Runtime in the process.
 *
 * Not thread-safe: every call (including [close]) must come from one owner thread,
 * except [prefetch] and [cancelPrefetch], which are thread-safe and may be called
 * from the producer thread while synthesis runs. The playback service owns it on
 * `readly-tts` via [SpeechEngine][app.readly.tts.SpeechEngine].
 *
 * @throws IOException if the voice file is missing or the model fails to load, naming
 *   the path and the real error.
 */
class PocketEngine(
    modelFile: File,
    g2pProvider: (() -> G2p)? = null,
    private val tuning: PocketTuning = PocketTuning(),
) : Closeable {

    private val env: OrtEnvironment
    private val session: OrtSession
    private val config: PocketConfig
    private val tokenizer: SentencePiece
    private val g2p: G2p?

    /**
     * Guards every [G2p.phonemise] call (inline synthesis and the prefetch
     * thread): the G2P ORT sessions run one call at a time, and the synth
     * thread waits on this lock rather than entering concurrently.
     */
    private val g2pLock = Any()
    private val prefetcher: V2Prefetcher?

    /** Flow-LM cache rows for the voice prompt, reused by every chunk. */
    private val voiceFlow: FloatArray
    private val voiceLen: Int
    private val rng = Rng()

    /** Output sample rate in Hz (24000 for the Farsi model). */
    val sampleRate: Int

    init {
        tuning.validate()
        if (!modelFile.exists()) {
            throw IOException("Pocket voice file is missing: ${modelFile.path}")
        }
        val path = modelFile.path
        env = OrtEnvironment.getEnvironment()
        session = try {
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(threadCount(Runtime.getRuntime().availableProcessors()))
                env.createSession(path, options)
            }
        } catch (e: OrtException) {
            throw IOException("Couldn't load the Pocket voice from $path: ${e.message}", e)
        }
        val metadata = try {
            session.metadata.customMetadata
        } catch (e: OrtException) {
            throw IOException("Couldn't load the Pocket voice from $path: ${e.message}", e)
        }
        config = readConfig(metadata, path)
        tokenizer = readTokenizer(metadata, path)
        g2p = if (config.isV2) {
            try {
                (g2pProvider ?: throw IOException(
                    "Couldn't load the Pocket voice from $path: this model speaks " +
                        "phonemes (v2) but no pronunciation model was provided",
                ))()
            } catch (e: IOException) {
                try {
                    session.close()
                } catch (closeError: OrtException) {
                    e.addSuppressed(closeError)
                }
                throw e
            }
        } else {
            null
        }
        val voice = readVoice(metadata, config, path)
        // Prefill the voice once; every chunk restarts from this cache.
        voiceLen = voice.frames
        voiceFlow = prefillVoice(voice.conditioning, voice.frames)
        sampleRate = config.sampleRate
        prefetcher = if (config.isV2) V2Prefetcher(::computePrefetch) else null
    }

    /**
     * Starts pronouncing [text] on the single prefetch thread (the whole v2
     * front end: normalize, split, clean, phonemise, translate, split pieces)
     * so the next chunk's synthesis can reuse it. Thread-safe; no-op for v1.
     * A G2P error here is stored, never reported: the inline call recomputes
     * and reports it with the existing message.
     */
    fun prefetch(text: String) {
        prefetcher?.prefetch(text.replace("\u0000", ""))
    }

    /** Cancels any prefetched work. Thread-safe; no-op for v1. */
    fun cancelPrefetch() {
        prefetcher?.cancelAll()
    }

    /**
     * Synthesizes [text] to mono float PCM at [sampleRate]. Generation stops early
     * (returning audio so far, no error) once [isStopped] returns true. [speed] is
     * clamped to 0.5..2.0 (non-finite becomes 1.0). [onChunk] streams each finished
     * piece — and the pause after a sentence/clause-final piece — for the system TTS service.
     *
     * Loudness is applied per chunk, so streaming and non-streaming sound the same.
     * Text that normalizes to empty is a logged skip returning no audio: readly sends
     * sentences that are only Latin or digits-and-symbols, and failing playback on
     * those would be wrong.
     */
    fun synthesize(
        text: String,
        speed: Float,
        isStopped: () -> Boolean,
        onChunk: ((FloatArray) -> Unit)? = null,
    ): FloatArray {
        val clean = text.replace("\u0000", "")
        // The clock starts before chunking so synthMs/rtf reflect all work,
        // including v2's per-sentence G2P (counted separately in g2pMs).
        val startMs = SystemClock.elapsedRealtime()
        var g2pMs = 0L
        var prefetched = false
        var prefetchWait = false
        val chunks = if (!config.isV2) {
            val normalized = NormalizeFa.normalize(clean)
            if (normalized.isEmpty()) {
                Log.i("Readly", "Pocket: nothing speakable in ${clean.take(40)}")
                return FloatArray(0)
            }
            PocketAudio.splitText(
                normalized,
                { piece -> tokenizer.encode(piece).size },
                tuning.maxTokens,
            ).map { TextChunk(it, PocketAudio.classifyPieceEnd(it)) }
        } else {
            val v2 = v2ChunksWithPrefetch(clean, isStopped)
            g2pMs = v2.g2pMs
            prefetched = v2.prefetched
            prefetchWait = v2.prefetchWait
            v2.chunks ?: return FloatArray(0)
        }
        if (chunks.isEmpty()) {
            Log.i("Readly", "Pocket: nothing speakable in ${clean.take(40)}")
            return FloatArray(0)
        }
        val clampedSpeed = if (speed.isFinite()) speed.coerceIn(0.5f, 2.0f) else 1.0f
        val finished = PocketAudio.finishSpeech(
            sequence {
                for (chunk in chunks) {
                    if (isStopped()) break
                    yield(PocketAudio.RawPiece(generateChunk(chunk.text, 0, isStopped), chunk.end))
                }
            },
            config.sampleRate,
            clampedSpeed,
            isStopped,
            onChunk,
        ) { SystemClock.elapsedRealtime() }
        val audio = finished.audio
        val masterMs = finished.masterMs
        val synthMs = SystemClock.elapsedRealtime() - startMs
        val audioMs = if (config.sampleRate > 0) audio.size * 1000.0 / config.sampleRate else 0.0
        val rtf = if (audioMs > 0) synthMs / audioMs else 0.0
        val g2pPart = when {
            prefetched -> "0 (prefetched)"
            prefetchWait -> "$g2pMs (prefetch wait)"
            else -> g2pMs.toString()
        }
        Log.i(
            "Readly",
            "synthesize: chars=${text.length} speed=$speed " +
                "synthMs=$synthMs g2pMs=$g2pPart masterMs=$masterMs audioMs=${audioMs.toLong()} " +
                "rtf=${String.format(Locale.US, "%.3f", rtf)}",
        )
        return audio
    }

    override fun close() {
        prefetcher?.close()
        try {
            session.close()
        } catch (e: OrtException) {
            throw IOException("Couldn't close the Pocket voice: ${e.message}", e)
        } finally {
            synchronized(g2pLock) {
                g2p?.close()
            }
        }
    }

    /** v2 chunk list plus the time spent in G2P (for the `synthesize:` log). */
    private data class V2Chunks(
        val chunks: List<TextChunk>?,
        val g2pMs: Long,
        val prefetched: Boolean = false,
        val prefetchWait: Boolean = false,
    )

    /**
     * v2 chunks, reusing a prefetched front end for exactly [clean]; a
     * prefetch still running for this text is awaited (its wait counts as
     * G2P time, so the log stays honest) instead of being cancelled and
     * recomputed. Only a different text or a failed/cancelled prefetch
     * computes inline below (which also reports G2P errors).
     */
    private fun v2ChunksWithPrefetch(clean: String, isStopped: () -> Boolean): V2Chunks {
        val p = prefetcher
        if (p != null) {
            when (val take = p.take(clean)) {
                is V2PrefetchTake.Ready -> return useReady(take.data, clean, 0, prefetched = true)
                is V2PrefetchTake.Pending -> {
                    val waitStart = SystemClock.elapsedRealtime()
                    when (val awaited = p.await(clean)) {
                        is V2PrefetchTake.Ready -> return useReady(
                            awaited.data,
                            clean,
                            SystemClock.elapsedRealtime() - waitStart,
                            prefetchWait = true,
                        )
                        is V2PrefetchTake.Miss, is V2PrefetchTake.Failed -> Unit
                        is V2PrefetchTake.Pending -> Unit // unreachable: await never pends
                    }
                }
                is V2PrefetchTake.Miss, is V2PrefetchTake.Failed -> Unit
            }
        }
        return v2Chunks(clean, isStopped)
    }

    /** Uses a prefetched front end, logging its blanks and fallback (like the inline path). */
    private fun useReady(
        data: V2PrefetchData,
        clean: String,
        g2pMs: Long,
        prefetched: Boolean = false,
        prefetchWait: Boolean = false,
    ): V2Chunks {
        for (blank in data.blanks) {
            Log.i("Readly", "Pocket: no phonemes for ${blank.take(40)}")
        }
        if (data.alignmentFallback) logAlignmentFallback()
        val chunks = data.chunks
        if (chunks == null) {
            Log.i("Readly", "Pocket: nothing speakable in ${clean.take(40)}")
            return V2Chunks(null, g2pMs, prefetched, prefetchWait)
        }
        return V2Chunks(chunks, g2pMs, prefetched, prefetchWait)
    }

    /**
     * The whole v2 front end for one clean chunk, for the prefetch thread: the
     * same normalize/split/clean/phonemise/translate/split as [v2Chunks] but
     * without stop checks or timing. Blank sentences are collected so the
     * synth thread can log them when the result is used. An [IOException]
     * from G2P propagates to the future (never logged here); the inline call
     * recomputes and reports it.
     */
    private fun computePrefetch(clean: String): V2PrefetchData {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val normalized = try {
            PocketTextV2.normalizeForModel(clean)
        } catch (e: IllegalArgumentException) {
            return V2PrefetchData(null, emptyList())
        }
        if (PocketTextV2.splitSentences(normalized).isEmpty()) {
            return V2PrefetchData(null, emptyList())
        }
        val plan = PocketTextV2.planV2Chunks(
            normalized,
            tuning,
            { cleaned -> phonemiseLocked(cleaned) },
            { piece -> tokenizer.encode(PocketTextV2.stripEzafe(piece)).size },
            { Thread.currentThread().isInterrupted },
        )
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        return V2PrefetchData(plan.chunks, plan.blanks, plan.alignmentFallback)
    }

    /** One G2P call, serialized with the prefetch thread on [g2pLock]. */
    private fun phonemiseLocked(clean: String): String =
        synchronized(g2pLock) {
            (g2p ?: error("v2 voice without a pronunciation model")).phonemise(clean)
        }

    /** Warns that clause alignment fell back to whole-sentence cuts (synth thread only). */
    private fun logAlignmentFallback() {
        Log.w(
            "Readly",
            "Pocket: fallback: true, attempted=clause_alignment, " +
                "reason=word_count_mismatch, taken=whole_sentence_cuts",
        )
    }

    /**
     * v2 chunk list from the shared [PocketTextV2.planV2Chunks]: normalize,
     * then plan each sentence's phonemes into tuning-budgeted pieces
     * (ezafe marks guide the split and are stripped before the model).
     * Null chunks mean a logged skip (nothing speakable).
     */
    private fun v2Chunks(clean: String, isStopped: () -> Boolean): V2Chunks {
        val normalized = try {
            PocketTextV2.normalizeForModel(clean)
        } catch (e: IllegalArgumentException) {
            Log.i("Readly", "Pocket: nothing speakable in ${clean.take(40)}")
            return V2Chunks(null, 0)
        }
        if (PocketTextV2.splitSentences(normalized).isEmpty()) {
            Log.i("Readly", "Pocket: nothing speakable in ${clean.take(40)}")
            return V2Chunks(null, 0)
        }
        var g2pMs = 0L
        val plan = PocketTextV2.planV2Chunks(
            normalized,
            tuning,
            { cleaned ->
                val g2pStart = SystemClock.elapsedRealtime()
                val phonemes = phonemiseLocked(cleaned)
                g2pMs += SystemClock.elapsedRealtime() - g2pStart
                phonemes
            },
            { piece -> tokenizer.encode(PocketTextV2.stripEzafe(piece)).size },
            isStopped,
        )
        for (blank in plan.blanks) {
            Log.i("Readly", "Pocket: no phonemes for ${blank.take(40)}")
        }
        if (plan.alignmentFallback) logAlignmentFallback()
        return V2Chunks(plan.chunks, g2pMs)
    }

    /** Generate one chunk in a single attempt (no runaway recovery). */
    private fun generateOnce(chunk: String, isStopped: () -> Boolean): FloatArray {
        val prepared = tokenizer.encode(
            PocketAudio.prepareTextPrompt(
                chunk,
                config.capitalizeFirstLetter,
                config.appendTerminalPunctuation,
            ),
        )
        if (prepared.isEmpty()) return FloatArray(0)
        val tokens = LongArray(prepared.size) { prepared[it].toLong() }
        val maxFrames = ceil(
            (tokens.size / config.tokensPerSecondEstimate + config.genSecondsPadding) *
                config.frameRate,
        ).toInt()

        val flowCache = Cache(config.flowStride(), voiceLen + 256)
        flowCache.append(voiceFlow)
        val mimiCache = Cache(
            config.mimiStride(),
            config.mimiKvLen + maxFrames * config.mimiStepsPerLatent,
        )
        var mimiConv = FloatArray(config.convStateSize)
        var mimiOffset = 0L

        // Text prefill: keep the flow cache, drop the audio.
        val textOut = runStep(
            seqData = FloatArray(0),
            seq = tokens.size,
            gates = TEXT_GATE,
            tokens = tokens,
            flowCache = flowCache,
            mimiCache = mimiCache,
            mimiOffset = mimiOffset,
            mimiConv = mimiConv,
            latentAndBos = null,
        )
        flowCache.append(textOut.flowKvNew)

        val frames = ArrayList<FloatArray>()
        var latent = FloatArray(config.latentDim)
        var eosFrame: Int? = null
        for (frame in 0 until maxFrames) {
            val out = runStepWithNoise(
                latent,
                if (frame == 0) 1f else 0f,
                flowCache,
                mimiCache,
                mimiOffset,
                mimiConv,
            )
            flowCache.append(out.flowKvNew)
            mimiCache.append(out.mimiKvNew)
            mimiConv = out.mimiConvOut
            mimiOffset = out.mimiOffsetOut
            latent = out.nextLatent
            if (eosFrame == null && out.eosLogit > tuning.eosThreshold) {
                eosFrame = frame
            }
            if (eosFrame != null && frame >= eosFrame + tuning.framesAfterEos) {
                break
            }
            frames += out.audio
            if (isStopped()) break
        }
        return concatFloats(frames)
    }

    /** Generate a chunk with runaway recovery: retry once, then split. */
    private fun generateChunk(text: String, depth: Int, isStopped: () -> Boolean): FloatArray {
        val tokens = tokenizer.encode(text).size
        val limit = PocketAudio.runawayLimitSeconds(
            tokens,
            config.tokensPerSecondEstimate,
            config.genSecondsPadding,
            config.frameRate,
        )
        val sampleRate = config.sampleRate.toDouble()
        var shortest: FloatArray? = null
        repeat(2) {
            if (isStopped()) return shortest ?: FloatArray(0)
            val audio = generateOnce(text, isStopped)
            if (audio.size / sampleRate <= limit) return audio
            if (shortest == null || audio.size < shortest!!.size) {
                shortest = audio
            }
        }
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (depth >= MAX_SPLIT_DEPTH || words.size < 4) {
            Log.i("Readly", "Pocket TTS: chunk still hit the length cap after splitting: $text")
            return shortest ?: FloatArray(0)
        }
        Log.i("Readly", "Pocket TTS: chunk hit the length cap; splitting ${words.size} words")
        val half = words.size / 2
        val first = generateChunk(words.subList(0, half).joinToString(" "), depth + 1, isStopped)
        if (isStopped()) return first
        val second = generateChunk(words.subList(half, words.size).joinToString(" "), depth + 1, isStopped)
        // A word cut takes the mid-sentence rule: 25 ms kept at each cut side, no pause.
        return concatFloats(
            listOf(
                PocketAudio.trimSilence(first, config.sampleRate, PocketAudio.TRIM_KEEP_MS, PocketAudio.TRIM_KEEP_MID_MS),
                PocketAudio.trimSilence(second, config.sampleRate, PocketAudio.TRIM_KEEP_MID_MS, PocketAudio.TRIM_KEEP_MS),
            ),
        )
    }

    /**
     * Run one streaming step. [seqData] is the tokens for a text step, the
     * conditioning for a voice step, and empty otherwise; the gate selects which.
     * Mirrors `_run` in the reference runtime. This is the zero-noise prefill path;
     * latent steps go through [runStepWithNoise].
     */
    private fun runStep(
        seqData: FloatArray,
        seq: Int,
        gates: FloatArray,
        tokens: LongArray,
        flowCache: Cache,
        mimiCache: Cache,
        mimiOffset: Long,
        mimiConv: FloatArray,
        latentAndBos: Pair<FloatArray, Float>?,
    ): StepOutput {
        val latent: FloatArray
        val isBos: FloatArray
        if (latentAndBos != null) {
            latent = latentAndBos.first.copyOf()
            isBos = FloatArray(seq) { latentAndBos.second }
        } else {
            latent = FloatArray(seq * config.latentDim)
            isBos = FloatArray(seq)
        }
        val cond = if (gates.contentEquals(COND_GATE)) {
            seqData.copyOf()
        } else {
            FloatArray(seq * config.modelDim)
        }
        val tokenData = if (gates.contentEquals(TEXT_GATE)) tokens.copyOf() else LongArray(seq)
        return runModel { inputs ->
            inputs["tokens"] = longTensor(env, tokenData, longArrayOf(1, seq.toLong()))
            inputs["latent"] = floatTensor(
                env, latent, longArrayOf(1, seq.toLong(), config.latentDim.toLong())
            )
            inputs["is_bos"] = floatTensor(env, isBos, longArrayOf(1, seq.toLong(), 1))
            inputs["cond"] = floatTensor(
                env, cond, longArrayOf(1, seq.toLong(), config.modelDim.toLong())
            )
            inputs["gates"] = floatTensor(env, gates.copyOf(), longArrayOf(3))
            inputs["noise"] = floatTensor(
                env, FloatArray(config.latentDim), longArrayOf(1, config.latentDim.toLong())
            )
            cacheInputs(inputs, flowCache, mimiCache, mimiOffset, mimiConv)
        }
    }

    /** Latent step with sampled noise (split out so [runStep] stays the prefill path). */
    private fun runStepWithNoise(
        latent: FloatArray,
        isBos: Float,
        flowCache: Cache,
        mimiCache: Cache,
        mimiOffset: Long,
        mimiConv: FloatArray,
    ): StepOutput {
        val noise = rng.noise(config.latentDim, tuning.temperature)
        return runModel { inputs ->
            inputs["tokens"] = longTensor(env, longArrayOf(0), longArrayOf(1, 1))
            inputs["latent"] = floatTensor(
                env, latent.copyOf(), longArrayOf(1, 1, config.latentDim.toLong())
            )
            inputs["is_bos"] = floatTensor(env, floatArrayOf(isBos), longArrayOf(1, 1, 1))
            inputs["cond"] = floatTensor(
                env, FloatArray(config.modelDim), longArrayOf(1, 1, config.modelDim.toLong())
            )
            inputs["gates"] = floatTensor(env, LATENT_GATE.copyOf(), longArrayOf(3))
            inputs["noise"] = floatTensor(
                env, noise, longArrayOf(1, config.latentDim.toLong())
            )
            cacheInputs(inputs, flowCache, mimiCache, mimiOffset, mimiConv)
        }
    }

    /** The cache inputs shared by both step paths (same names, shapes and order). */
    private fun cacheInputs(
        inputs: MutableMap<String, OnnxTensor>,
        flowCache: Cache,
        mimiCache: Cache,
        mimiOffset: Long,
        mimiConv: FloatArray,
    ) {
        inputs["flow_kv"] = floatTensor(
            env,
            flowCache.window(),
            longArrayOf(
                flowCache.length.toLong(),
                config.flowLayers.toLong(),
                2,
                1,
                config.flowHeads.toLong(),
                config.flowHeadDim.toLong(),
            ),
        )
        inputs["flow_offset"] = longTensor(env, longArrayOf(flowCache.length.toLong()), longArrayOf())
        inputs["mimi_kv"] = floatTensor(
            env,
            mimiCache.windowSized(config.mimiKvLen),
            longArrayOf(
                config.mimiKvLen.toLong(),
                config.mimiLayers.toLong(),
                2,
                1,
                config.mimiHeads.toLong(),
                config.mimiHeadDim.toLong(),
            ),
        )
        inputs["mimi_offset"] = longTensor(env, longArrayOf(mimiOffset), longArrayOf())
        inputs["mimi_conv"] = floatTensor(
            env, mimiConv.copyOf(), longArrayOf(config.convStateSize.toLong())
        )
        inputs["decode_steps"] = floatTensor(
            env,
            floatArrayOf(
                if (tuning.decodeSteps == 0) {
                    config.samplerDecodeSteps
                } else {
                    tuning.decodeSteps.toFloat()
                },
            ),
            longArrayOf(),
        )
    }

    /**
     * Builds the named inputs, runs one step, and reads the outputs. Every input
     * tensor and the result are closed; a missing output names itself.
     */
    private fun runModel(build: (MutableMap<String, OnnxTensor>) -> Unit): StepOutput {
        val inputs = LinkedHashMap<String, OnnxTensor>()
        try {
            build(inputs)
            session.run(inputs).use { result -> return readStepOutput(result) }
        } catch (e: OrtException) {
            throw IOException("Pocket model step failed: ${e.message}", e)
        } finally {
            for (tensor in inputs.values) tensor.close()
        }
    }

    private fun readStepOutput(result: OrtSession.Result): StepOutput {
        fun floats(name: String): FloatArray {
            val value = result.get(name).orElse(null)
                ?: throw IOException("Pocket model gave no '$name' output")
            val buffer = (value as OnnxTensor).floatBuffer
            return FloatArray(buffer.remaining()).also { buffer.get(it) }
        }
        val eos = floats("eos_logit")
        val offsetValue = result.get("mimi_offset_out").orElse(null)
            ?: throw IOException("Pocket model gave no 'mimi_offset_out' output")
        val offsetBuffer = (offsetValue as OnnxTensor).longBuffer
        return StepOutput(
            audio = floats("audio"),
            nextLatent = floats("next_latent"),
            eosLogit = if (eos.isNotEmpty()) eos[0] else Float.NEGATIVE_INFINITY,
            flowKvNew = floats("flow_kv_new"),
            mimiKvNew = floats("mimi_kv_new"),
            mimiOffsetOut = if (offsetBuffer.remaining() > 0) offsetBuffer.get() else 0L,
            mimiConvOut = floats("mimi_conv_out"),
        )
    }

    private fun prefillVoice(conditioning: FloatArray, frames: Int): FloatArray {
        val flowCache = Cache(config.flowStride(), frames + 1)
        val mimiCache = Cache(config.mimiStride(), config.mimiKvLen)
        return runStep(
            seqData = conditioning,
            seq = frames,
            gates = COND_GATE,
            tokens = LongArray(0),
            flowCache = flowCache,
            mimiCache = mimiCache,
            mimiOffset = 0L,
            mimiConv = FloatArray(config.convStateSize),
            latentAndBos = null,
        ).flowKvNew
    }

    private data class StepOutput(
        val audio: FloatArray,
        val nextLatent: FloatArray,
        val eosLogit: Float,
        val flowKvNew: FloatArray,
        val mimiKvNew: FloatArray,
        val mimiOffsetOut: Long,
        val mimiConvOut: FloatArray,
    )

    companion object {
        private const val MAX_SPLIT_DEPTH = 2

        /**
         * Synthesis threads: one core stays free for G2P prefetch and
         * playback, and past 4 threads the phone gains nothing.
         */
        internal const val MIN_THREADS = 2
        internal const val MAX_THREADS = 4

        /** Synthesis threads for a host with [availableProcessors] cores. */
        internal fun threadCount(availableProcessors: Int): Int =
            (availableProcessors - 1).coerceIn(MIN_THREADS, MAX_THREADS)

        private val TEXT_GATE = floatArrayOf(1f, 0f, 0f)
        private val LATENT_GATE = floatArrayOf(0f, 1f, 0f)
        private val COND_GATE = floatArrayOf(0f, 0f, 1f)

        private fun readConfig(metadata: Map<String, String>, path: String): PocketConfig {
            fun fail(detail: String): Nothing =
                throw IOException("Couldn't load the Pocket voice from $path: $detail")
            val text = metadata["pocket_tts_config"]
                ?: fail("model has no pocket_tts_config metadata")
            val json = try {
                JSONObject(text)
            } catch (e: JSONException) {
                fail("model config is unreadable: ${e.message}")
            }
            val config = try {
                val voices = json.getJSONObject("voices")
                PocketConfig(
                    sampleRate = json.getInt("sample_rate"),
                    frameRate = json.getDouble("frame_rate"),
                    frameSize = json.optInt("frame_size", 0),
                    latentDim = json.getInt("latent_dim"),
                    modelDim = json.getInt("model_dim"),
                    flowLayers = json.getInt("flow_layers"),
                    flowHeads = json.getInt("flow_heads"),
                    flowHeadDim = json.getInt("flow_head_dim"),
                    mimiLayers = json.getInt("mimi_layers"),
                    mimiHeads = json.getInt("mimi_heads"),
                    mimiHeadDim = json.getInt("mimi_head_dim"),
                    mimiKvLen = json.getInt("mimi_kv_len"),
                    mimiStepsPerLatent = json.getInt("mimi_steps_per_latent"),
                    convStateSize = json.getInt("conv_state_size"),
                    samplerDecodeSteps = json.getDouble("sampler_decode_steps").toFloat(),
                    tokensPerSecondEstimate = json.getDouble("tokens_per_second_estimate"),
                    genSecondsPadding = json.getDouble("gen_seconds_padding"),
                    capitalizeFirstLetter = json.optBoolean("capitalize_first_letter", true),
                    appendTerminalPunctuation = json.optBoolean("append_terminal_punctuation", true),
                    voices = buildMap {
                        for (name in voices.keys()) {
                            val shape = voices.getJSONArray(name)
                            put(name, listOf(shape.getInt(0), shape.getInt(1)))
                        }
                    },
                )
            } catch (e: JSONException) {
                fail("model config is unreadable: ${e.message}")
            }
            if (config.sampleRate == 0 || config.latentDim == 0 || config.modelDim == 0 ||
                config.frameSize == 0
            ) {
                fail("model config has a zero sample rate or dimension")
            }
            if (json.optBoolean("pad_with_spaces_for_short_inputs", false)) {
                fail("model wants space padding for short inputs, which readly doesn't implement")
            }
            return config
        }

        private fun readTokenizer(metadata: Map<String, String>, path: String): SentencePiece {
            fun fail(detail: String): Nothing =
                throw IOException("Couldn't load the Pocket voice from $path: $detail")
            val encoded = metadata["pocket_tts_tokenizer"]
                ?: fail("model has no pocket_tts_tokenizer metadata")
            val bytes = try {
                Base64.getDecoder().decode(encoded)
            } catch (e: IllegalArgumentException) {
                fail("model tokenizer is not base64: ${e.message}")
            }
            try {
                return SentencePiece.fromBytes(bytes)
            } catch (e: IOException) {
                fail(e.message ?: "voice tokenizer is unreadable")
            }
        }

        private fun readVoice(
            metadata: Map<String, String>,
            config: PocketConfig,
            path: String,
        ): BakedVoice {
            fun fail(detail: String): Nothing =
                throw IOException("Couldn't load the Pocket voice from $path: $detail")
            val (name, shape) = config.voices.entries.firstOrNull()
                ?: fail("model carries no baked voice")
            val encoded = metadata["pocket_tts_voice/$name"]
                ?: fail("model voice '$name' data is missing")
            val conditioning = try {
                decodeF16Base64(encoded)
            } catch (e: IOException) {
                fail(e.message ?: "voice data is unreadable")
            }
            val expected = shape[0] * shape[1]
            if (conditioning.size != expected) {
                fail("model voice has ${conditioning.size} values, config says $expected")
            }
            if (shape.size != 2 || shape[1] != config.modelDim) {
                fail("model voice shape $shape is not [frames, model_dim]")
            }
            return BakedVoice(name, shape[0], conditioning)
        }

        private fun floatTensor(env: OrtEnvironment, data: FloatArray, shape: LongArray): OnnxTensor {
            val buffer = ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            buffer.put(data)
            buffer.rewind()
            return OnnxTensor.createTensor(env, buffer, shape)
        }

        private fun longTensor(env: OrtEnvironment, data: LongArray, shape: LongArray): OnnxTensor {
            val buffer = ByteBuffer.allocateDirect(data.size * 8)
                .order(ByteOrder.nativeOrder()).asLongBuffer()
            buffer.put(data)
            buffer.rewind()
            return OnnxTensor.createTensor(env, buffer, shape)
        }

        private fun concatFloats(parts: List<FloatArray>): FloatArray {
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

/** Model geometry and defaults baked into the ONNX metadata. */
private data class PocketConfig(
    val sampleRate: Int,
    val frameRate: Double,
    val frameSize: Int,
    val latentDim: Int,
    val modelDim: Int,
    val flowLayers: Int,
    val flowHeads: Int,
    val flowHeadDim: Int,
    val mimiLayers: Int,
    val mimiHeads: Int,
    val mimiHeadDim: Int,
    val mimiKvLen: Int,
    val mimiStepsPerLatent: Int,
    val convStateSize: Int,
    val samplerDecodeSteps: Float,
    val tokensPerSecondEstimate: Double,
    val genSecondsPadding: Double,
    val capitalizeFirstLetter: Boolean,
    val appendTerminalPunctuation: Boolean,
    val voices: Map<String, List<Int>>,
) {
    /** v2 speaks G2P phonemes; anything else is the v1 Persian-script path. */
    val isV2: Boolean get() = !capitalizeFirstLetter && !appendTerminalPunctuation

    fun flowStride(): Int = flowLayers * 2 * flowHeads * flowHeadDim

    fun mimiStride(): Int = mimiLayers * 2 * mimiHeads * mimiHeadDim
}

private data class BakedVoice(val name: String, val frames: Int, val conditioning: FloatArray)

/** One text chunk to generate, plus how its end sounds. */
internal data class TextChunk(val text: String, val end: PocketAudio.PieceEnd)

/**
 * A prefetched v2 front end: the chunk list (null when nothing was speakable),
 * the sentences whose phonemes came back blank, and whether clause
 * alignment fell back to whole-sentence cuts, so the synth thread can log
 * them when the result is used (never logged on the prefetch thread).
 */
internal data class V2PrefetchData(
    val chunks: List<TextChunk>?,
    val blanks: List<String>,
    val alignmentFallback: Boolean = false,
)

/** Outcome of asking the prefetcher for one exact clean text. */
internal sealed interface V2PrefetchTake {
    /** Ready to use (consumed: a second take for the same text misses). */
    data class Ready(val data: V2PrefetchData) : V2PrefetchTake

    /** Still computing; the caller waits for it ([V2Prefetcher.await]). */
    object Pending : V2PrefetchTake

    /** Nothing was prefetched for this text. */
    object Miss : V2PrefetchTake

    /** Prefetch failed or was cancelled; the inline call recomputes. */
    object Failed : V2PrefetchTake
}

/**
 * Pronounces v2 chunks one text ahead on a single background thread. The cache
 * is keyed by the exact clean text; a take for any other text misses, and a
 * compute failure is stored (never thrown here) so the inline path reports it.
 * Pure JVM (no Android or ORT): [compute] carries the front end, so tests use
 * a fake. Thread-safe.
 */
internal class V2Prefetcher(private val compute: (String) -> V2PrefetchData) : Closeable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "readly-g2p-prefetch").apply { isDaemon = true }
    }
    private val lock = Any()
    private val cache = LinkedHashMap<String, Future<V2PrefetchData>>()

    @Volatile
    private var closed = false

    /** Starts computing [text] unless it is already cached or computing. */
    fun prefetch(text: String) {
        if (closed) return
        synchronized(lock) {
            if (cache.containsKey(text)) return
            while (cache.size >= MAX_CACHED) {
                val eldest = cache.entries.iterator().next()
                cache.remove(eldest.key)
                if (!eldest.value.isDone) eldest.value.cancel(true)
            }
            val future = try {
                executor.submit(Callable { compute(text) })
            } catch (e: RejectedExecutionException) {
                return
            }
            cache[text] = future
        }
    }

    /** Takes the prefetched result for [text], if it finished successfully. */
    fun take(text: String): V2PrefetchTake {
        val future = synchronized(lock) { cache[text] } ?: return V2PrefetchTake.Miss
        if (!future.isDone) return V2PrefetchTake.Pending
        synchronized(lock) { cache.remove(text) }
        return try {
            V2PrefetchTake.Ready(future.get())
        } catch (e: CancellationException) {
            V2PrefetchTake.Miss
        } catch (e: ExecutionException) {
            V2PrefetchTake.Failed
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            V2PrefetchTake.Failed
        }
    }

    /**
     * Waits for the prefetched result for [text] and consumes it, like
     * [take]. Never returns [V2PrefetchTake.Pending]: a stale entry (a
     * different text, or one cancelled while waiting) misses, so the caller
     * computes inline. A caller interrupt is retried inline too. Thread-safe.
     */
    fun await(text: String): V2PrefetchTake {
        val future = synchronized(lock) { cache[text] } ?: return V2PrefetchTake.Miss
        return try {
            val data = future.get()
            synchronized(lock) { cache.remove(text) }
            V2PrefetchTake.Ready(data)
        } catch (e: CancellationException) {
            synchronized(lock) { cache.remove(text) }
            V2PrefetchTake.Miss
        } catch (e: ExecutionException) {
            synchronized(lock) { cache.remove(text) }
            V2PrefetchTake.Failed
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            V2PrefetchTake.Failed
        }
    }

    /** Drops the prefetched work for [text], cancelling it when still running. */
    fun cancelFor(text: String) {
        synchronized(lock) {
            cache.remove(text)?.cancel(true)
        }
    }

    /** Drops all prefetched work. */
    fun cancelAll() {
        synchronized(lock) {
            for (future in cache.values) future.cancel(true)
            cache.clear()
        }
    }

    override fun close() {
        closed = true
        cancelAll()
        executor.shutdownNow()
    }

    companion object {
        private const val MAX_CACHED = 4
    }
}

/** Grow-once token-position cache, mirroring the reference `_Cache`. */
internal class Cache(private val stride: Int, capacity: Int) {
    private var buffer = FloatArray(capacity * stride)
    var length = 0
        private set

    private fun reserve(extra: Int) {
        val needed = length + extra
        val capacityNow = buffer.size / stride
        if (needed > capacityNow) {
            val grown = FloatArray(maxOf(needed, capacityNow * 2) * stride)
            buffer.copyInto(grown, 0, 0, length * stride)
            buffer = grown
        }
    }

    fun append(values: FloatArray) {
        val frames = values.size / stride
        reserve(frames)
        values.copyInto(buffer, length * stride, 0, values.size)
        length += frames
    }

    /** The full past, for the flow cache. */
    fun window(): FloatArray = buffer.copyOfRange(0, length * stride)

    /** The last [size] entries, zero-padded at the front while still short. */
    fun windowSized(size: Int): FloatArray {
        if (length >= size) {
            val start = (length - size) * stride
            return buffer.copyOfRange(start, length * stride)
        }
        val padded = FloatArray(size * stride)
        buffer.copyInto(padded, (size - length) * stride, 0, length * stride)
        return padded
    }
}

/**
 * Small self-contained Gaussian RNG (SplitMix64 + Box–Muller). Sampling needs no
 * extra dependency, and a fresh stream per engine is fine.
 */
internal class Rng {
    private val random = SplittableRandom(System.nanoTime())
    private var spare: Float? = null

    private fun nextUnit(): Float {
        // 53-bit uniform in (0, 1]; SplitMix can return exactly 0.
        val value = random.nextDouble().toFloat()
        return if (value <= 0f) 1f else value
    }

    /** Box–Muller gaussian, caching the spare sample like the ref. */
    fun gaussian(): Float {
        spare?.let {
            spare = null
            return it
        }
        val u1 = nextUnit()
        val u2 = nextUnit()
        val radius = sqrt(-2f * ln(u1))
        val theta = 2f * PI.toFloat() * u2
        spare = radius * sin(theta)
        return radius * cos(theta)
    }

    /** [dim] gaussians scaled by `sqrt(temperature)`, as in the ref. */
    fun noise(dim: Int, temperature: Float): FloatArray {
        val std = sqrt(temperature)
        return FloatArray(dim) { gaussian() * std }
    }
}

/** IEEE-754 half → single conversion for the baked voice conditioning. */
internal fun f16ToF32(bits: Int): Float {
    val sign = (bits ushr 15) shl 31
    val exponent = (bits ushr 10) and 0x1f
    val fraction = bits and 0x3ff
    val out = when {
        exponent == 0 && fraction == 0 -> sign
        exponent == 0 -> {
            // Subnormal half: normalize.
            var e = -14
            var f = fraction
            while (f and 0x400 == 0) {
                f = f shl 1
                e -= 1
            }
            f = f and 0x3ff
            sign or ((e + 127) shl 23) or (f shl 13)
        }
        exponent == 31 -> sign or 0x7f80_0000 or (fraction shl 13)
        else -> sign or ((exponent - 15 + 127) shl 23) or (fraction shl 13)
    }
    return Float.fromBits(out)
}

/** The baked voice conditioning: base64 of little-endian f16 values. */
internal fun decodeF16Base64(text: String): FloatArray {
    val bytes = try {
        Base64.getDecoder().decode(text)
    } catch (e: IllegalArgumentException) {
        throw IOException("voice data is not base64: ${e.message}")
    }
    if (bytes.size % 2 != 0) {
        throw IOException("voice data has an odd byte count")
    }
    return FloatArray(bytes.size / 2) { i ->
        val lo = bytes[2 * i].toInt() and 0xff
        val hi = bytes[2 * i + 1].toInt() and 0xff
        f16ToF32(lo or (hi shl 8))
    }
}
