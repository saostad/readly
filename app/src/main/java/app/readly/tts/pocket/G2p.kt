package app.readly.tts.pocket

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Persian pronunciation (G2P) for Pocket TTS Farsi v2: Homo-GE2PE-Persian-HF
 * (tiny ByT5) as encoder + KV-cache decoder ONNX. One cleaned Persian
 * sentence goes in, one phoneme string comes out, via the same beam search
 * `transformers` runs for `generate(num_beams=5, max_length=512,
 * early_stopping=True)` (length penalty 1.0): the encoder computes each
 * decoder layer's cross-attention K/V once, and every step feeds one token
 * plus the past self K/V, reordered by the beam selection like HF's cache.
 * The numpy version in `tools/pocket-export/export_g2p.py` is the middle
 * port, and all three agree byte for byte on every golden sentence.
 *
 * Not thread-safe: every call (including [close]) must come from one owner
 * thread. The [PocketEngine][app.readly.tts.pocket.PocketEngine] owns it on
 * `readly-tts` for v2 voices.
 */
class G2p private constructor(encoderBytes: ByteArray, decoderBytes: ByteArray) : Closeable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val encoder: OrtSession
    private val decoder: OrtSession

    init {
        encoder = openSession(encoderBytes)
        decoder = try {
            openSession(decoderBytes)
        } catch (e: Exception) {
            encoder.close()
            throw e
        }
    }

    private fun openSession(bytes: ByteArray): OrtSession {
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(INTRA_OP_THREADS)
            options.setInterOpNumThreads(INTER_OP_THREADS)
            return env.createSession(bytes, options)
        }
    }

    /**
     * Pronounces one cleaned Persian sentence (reference
     * `clean_sentence_for_g2p` output): beam search over the decoder, ByT5
     * byte ids, specials skipped. Empty input returns empty output.
     *
     * @throws IOException if a pronunciation step fails, naming the sentence
     *   and the real error.
     */
    fun phonemise(clean: String): String {
        if (clean.isEmpty()) return ""
        try {
            val ids = encodeBytes(clean)
            val cross = runEncoder(ids)
            val best = search(cross)
            return decodeBytes(best)
        } catch (e: Exception) {
            throw IOException(
                "Pronunciation step failed for \"${clean.take(40)}\": " +
                    "${e.javaClass.name}: ${e.message}",
                e,
            )
        }
    }

    override fun close() {
        try {
            try {
                encoder.close()
            } finally {
                decoder.close()
            }
        } catch (e: Exception) {
            throw IOException(
                "Couldn't close the Persian pronunciation model: ${e.javaClass.name}: ${e.message}",
                e,
            )
        }
    }

    /** Per-layer cross-attention K/V for one sentence (batch 1). */
    private data class CrossKv(
        val k0: FloatArray,
        val v0: FloatArray,
        val k1: FloatArray,
        val v1: FloatArray,
        val encLen: Int,
    )

    private fun runEncoder(ids: LongArray): CrossKv {
        val input = longTensor(ids, longArrayOf(1, ids.size.toLong()))
        try {
            encoder.run(mapOf("input_ids" to input)).use { result ->
                val k0 = floats(result, "cross_k_0")
                val encLen = k0.size / NUM_HEADS / D_KV
                return CrossKv(
                    k0,
                    floats(result, "cross_v_0"),
                    floats(result, "cross_k_1"),
                    floats(result, "cross_v_1"),
                    encLen,
                )
            }
        } finally {
            input.close()
        }
    }

    private fun search(cross: CrossKv): List<Long> {
        // Every beam reads the same cross K/V; repeat once up front.
        val tensors = ArrayList<OnnxTensor>()
        try {
            val crossTensors = mapOf(
                "cross_k_0" to repeatBeams(cross.k0),
                "cross_v_0" to repeatBeams(cross.v0),
                "cross_k_1" to repeatBeams(cross.k1),
                "cross_v_1" to repeatBeams(cross.v1),
            ).mapValues { (_, data) ->
                floatTensor(
                    data,
                    longArrayOf(
                        NUM_BEAMS.toLong(), NUM_HEADS.toLong(),
                        cross.encLen.toLong(), D_KV.toLong(),
                    ),
                ).also { tensors += it }
            }
            // Past self K/V per layer, [beam, head, past, d], grown by the
            // decoder each step and reordered by the beam selection.
            var pastK0 = FloatArray(0)
            var pastV0 = FloatArray(0)
            var pastK1 = FloatArray(0)
            var pastV1 = FloatArray(0)
            val running = Array(NUM_BEAMS) { mutableListOf(PAD_ID) }
            val runningScores = DoubleArray(NUM_BEAMS) { if (it == 0) 0.0 else -1e9 }
            val finalized = Array(NUM_BEAMS) { Triple(listOf(PAD_ID), -1e9, false) }
            var curLen = 1
            var heuristicOk = true
            while (true) {
                val pastLen = curLen - 1
                val stepTensors = HashMap<String, OnnxTensor>()
                try {
                    stepTensors["decoder_input_ids"] = longTensor(
                        LongArray(NUM_BEAMS) { running[it].last() },
                        longArrayOf(NUM_BEAMS.toLong(), 1),
                    )
                    stepTensors["self_bias_buckets"] = longTensor(
                        selfBiasBuckets(pastLen),
                        longArrayOf((pastLen + 1).toLong()),
                    )
                    val pastShape = longArrayOf(
                        NUM_BEAMS.toLong(), NUM_HEADS.toLong(),
                        pastLen.toLong(), D_KV.toLong(),
                    )
                    stepTensors["past_k_0"] = floatTensor(pastK0, pastShape)
                    stepTensors["past_v_0"] = floatTensor(pastV0, pastShape)
                    stepTensors["past_k_1"] = floatTensor(pastK1, pastShape)
                    stepTensors["past_v_1"] = floatTensor(pastV1, pastShape)
                    stepTensors.putAll(crossTensors)
                    val (logits, present) = decoder.run(stepTensors).use { result ->
                        floats(result, "logits") to arrayOf(
                            floats(result, "present_k_0"),
                            floats(result, "present_v_0"),
                            floats(result, "present_k_1"),
                            floats(result, "present_v_1"),
                        )
                    }
                    val next = selectBeams(logits, running, runningScores, finalized, heuristicOk, curLen)
                    runningScores.indices.forEach {
                        running[it] = next.seqs[it]
                        runningScores[it] = next.scores[it]
                    }
                    for (b in 0 until NUM_BEAMS) finalized[b] = next.finalized[b]
                    heuristicOk = next.heuristicOk
                    // Reorder the grown cache by the selected beams.
                    pastK0 = gatherBeams(present[0], next.beams, pastLen + 1)
                    pastV0 = gatherBeams(present[1], next.beams, pastLen + 1)
                    pastK1 = gatherBeams(present[2], next.beams, pastLen + 1)
                    pastV1 = gatherBeams(present[3], next.beams, pastLen + 1)
                    curLen += 1
                    if (!next.keepGoing) break
                } finally {
                    for ((name, tensor) in stepTensors) {
                        if (!crossTensors.containsKey(name)) tensor.close()
                    }
                }
            }
            val best = finalized.maxByOrNull { it.second }!!.first
            val eosAt = best.indexOf(EOS_ID)
            val trimmed = if (eosAt < 0) best else best.subList(0, eosAt)
            return trimmed.filter { it != PAD_ID }
        } finally {
            for (tensor in tensors) tensor.close()
        }
    }

    /** One beam-selection round over a step's logits (HF `_beam_search`). */
    private data class BeamPick(
        val seqs: List<MutableList<Long>>,
        val scores: DoubleArray,
        val beams: IntArray,
        val finalized: List<Triple<List<Long>, Double, Boolean>>,
        val heuristicOk: Boolean,
        val keepGoing: Boolean,
    )

    private fun selectBeams(
        logits: FloatArray,
        running: Array<MutableList<Long>>,
        runningScores: DoubleArray,
        finalized: Array<Triple<List<Long>, Double, Boolean>>,
        heuristicOk: Boolean,
        curLen: Int,
    ): BeamPick {
        // Log-softmax of each beam's one position, plus its score.
        val flat = DoubleArray(NUM_BEAMS * VOCAB_SIZE)
        for (b in 0 until NUM_BEAMS) {
            val base = b * VOCAB_SIZE
            var max = Double.NEGATIVE_INFINITY
            for (v in 0 until VOCAB_SIZE) {
                val x = logits[base + v].toDouble()
                if (x > max) max = x
            }
            var sum = 0.0
            for (v in 0 until VOCAB_SIZE) sum += exp(logits[base + v] - max)
            val logSum = ln(sum)
            for (v in 0 until VOCAB_SIZE) {
                flat[b * VOCAB_SIZE + v] =
                    logits[base + v] - max - logSum + runningScores[b]
            }
        }
        // Stable top-K: score desc, flat index asc.
        val order = (flat.indices).sortedWith(compareBy({ -flat[it] }, { it }))
        val topk = order.take(BEAMS_TO_KEEP)
        val topkScores = topk.map { flat[it] }
        val topkBeams = topk.map { it / VOCAB_SIZE }
        val topkSeqs = topk.map { running[it / VOCAB_SIZE] + (it % VOCAB_SIZE).toLong() }
        val hitsStop = topkSeqs.map { it.last() == EOS_ID || it.size >= MAX_LENGTH }
        // Running beams for the next step: best non-finished top-K.
        val masked = topkScores.mapIndexed { i, s -> if (hitsStop[i]) s - 1e9 else s }
        val next = masked.indices.sortedWith(compareBy({ -masked[it] }, { it })).take(NUM_BEAMS)
        val seqs = next.map { topkSeqs[it].toMutableList() }
        val scores = DoubleArray(NUM_BEAMS) { masked[next[it]] }
        val beams = IntArray(NUM_BEAMS) { topkBeams[next[it]] }
        // Finalize: finished beams among the top num_beams only,
        // merged with the previous finalized set, best kept. Ties
        // keep the earlier entry, as in the reference.
        // cur_len + 1 tokens, decoder prompt len 1.
        val genLen = curLen.toDouble().pow(LENGTH_PENALTY)
        val fresh = topk.indices.map { i ->
            var score = topkScores[i] / genLen
            if (finalized.all { it.third }) score = -1e9
            if (!heuristicOk) score = -1e9
            val justDone = hitsStop[i] && i < NUM_BEAMS
            if (!justDone) score = -1e9
            Triple(topkSeqs[i], score, justDone)
        }
        val merged = finalized.toList() + fresh
        val best = merged.indices.sortedWith(
            compareBy({ -merged[it].second }, { it }),
        ).take(NUM_BEAMS)
        val done = best.map { merged[it] }
        // Early-stop heuristic: the best running score, penalized at
        // the hypothetical best length, can still beat the worst
        // finished beam.
        val hypoLen = curLen.toDouble().pow(LENGTH_PENALTY)
        val bestRunning = scores[0] / hypoLen
        val worstDone = done.filter { it.third }.minOfOrNull { it.second } ?: -1e9
        val stillOk = heuristicOk && bestRunning > worstDone
        val openBeam = !done.all { it.third }
        val keepGoing = stillOk && openBeam && !hitsStop.all { it }
        return BeamPick(seqs, scores, beams, done, stillOk, keepGoing)
    }

    /** Repeats one batch-1 [head, len, d] cache to all beams. */
    private fun repeatBeams(single: FloatArray): FloatArray {
        return FloatArray(single.size * NUM_BEAMS) { single[it % single.size] }
    }

    /**
     * Gathers one beam slice per selected beam from a grown
     * [beam, head, len, d] cache (HF `_reorder_cache`).
     */
    private fun gatherBeams(present: FloatArray, beams: IntArray, len: Int): FloatArray {
        val beamSize = NUM_HEADS * len * D_KV
        val out = FloatArray(beams.size * beamSize)
        for (b in beams.indices) {
            present.copyInto(out, b * beamSize, beams[b] * beamSize, (beams[b] + 1) * beamSize)
        }
        return out
    }
    private fun floats(result: OrtSession.Result, name: String): FloatArray {
        val value = result.get(name).orElse(null)
            ?: throw IOException("Pronunciation model gave no '$name' output")
        val buffer = (value as OnnxTensor).floatBuffer
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun floatTensor(data: FloatArray, shape: LongArray): OnnxTensor {
        val buffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(data)
        buffer.rewind()
        return OnnxTensor.createTensor(env, buffer, shape)
    }

    private fun longTensor(data: LongArray, shape: LongArray): OnnxTensor {
        val buffer = ByteBuffer.allocateDirect(data.size * 8)
            .order(ByteOrder.nativeOrder()).asLongBuffer()
        buffer.put(data)
        buffer.rewind()
        return OnnxTensor.createTensor(env, buffer, shape)
    }

    companion object {
        private const val PAD_ID = 0L
        private const val EOS_ID = 1L
        private const val BYTE_OFFSET = 3
        private const val FIRST_BYTE_ID = 3L
        private const val LAST_BYTE_ID = 258L
        private const val VOCAB_SIZE = 384
        private const val NUM_HEADS = 6
        private const val D_KV = 64
        private const val NUM_BEAMS = 5
        private const val BEAMS_TO_KEEP = 10
        private const val MAX_LENGTH = 512
        private const val LENGTH_PENALTY = 1.0
        // GE2PE config (relative_attention_num_buckets/max_distance); the
        // parity test guards these (every step's bias comes from them).
        private const val NUM_BUCKETS = 32
        private const val MAX_DISTANCE = 128

        /**
         * G2P runs beside Pocket synthesis (prefetch), so each session takes
         * one core: a single intra-op and inter-op thread.
         */
        internal const val INTRA_OP_THREADS = 1
        internal const val INTER_OP_THREADS = 1

        private const val ENCODER_ASSET = "ge2pe/encoder.onnx"
        private const val DECODER_ASSET = "ge2pe/decoder.onnx"

        /**
         * Loads the pronunciation model from app assets (`ge2pe/`).
         *
         * @throws IOException naming the real error when the model can't load.
         */
        fun fromAssets(assets: AssetManager): G2p {
            try {
                val encoder = assets.open(ENCODER_ASSET).use { it.readBytes() }
                val decoder = assets.open(DECODER_ASSET).use { it.readBytes() }
                return G2p(encoder, decoder)
            } catch (e: Exception) {
                throw IOException(
                    "Couldn't load the Persian pronunciation model: ${e.javaClass.name}: ${e.message}",
                    e,
                )
            }
        }

        /** Loads the pronunciation model from two ONNX files (tests). */
        fun fromFiles(encoder: File, decoder: File): G2p {
            try {
                return G2p(encoder.readBytes(), decoder.readBytes())
            } catch (e: Exception) {
                throw IOException(
                    "Couldn't load the Persian pronunciation model: ${e.javaClass.name}: ${e.message}",
                    e,
                )
            }
        }

        /** ByT5 byte ids: UTF-8 byte b → id b + 3. */
        internal fun encodeBytes(text: String): LongArray {
            val bytes = text.toByteArray(Charsets.UTF_8)
            return LongArray(bytes.size) { ((bytes[it].toInt() and 0xff) + BYTE_OFFSET).toLong() }
        }

        /**
         * Relative-position buckets for one decode step: query position
         * [pastLen] over keys 0..pastLen, decoder-style (bidirectional =
         * false). Exact port of `T5Attention._relative_position_bucket` (and
         * of `self_bias_buckets` in `export_g2p.py`, which the export check
         * proves against HF on every reachable length).
         */
        internal fun selfBiasBuckets(pastLen: Int): LongArray {
            val maxExact = NUM_BUCKETS / 2
            return LongArray(pastLen + 1) { key ->
                val rel = pastLen - key
                if (rel < maxExact) {
                    rel.toLong()
                } else {
                    val large = maxExact + (
                        ln(rel.toDouble() / maxExact) /
                            ln(MAX_DISTANCE.toDouble() / maxExact) *
                            (NUM_BUCKETS - maxExact)
                        ).toInt()
                    minOf(large, NUM_BUCKETS - 1).toLong()
                }
            }
        }

        /**
         * ByT5 decode: ids 3..258 → bytes, everything else skipped, then
         * UTF-8 with malformed input ignored (reference `convert_tokens_to_string`).
         */
        internal fun decodeBytes(ids: List<Long>): String {
            val bytes = ids.filter { it in FIRST_BYTE_ID..LAST_BYTE_ID }
                .map { (it - BYTE_OFFSET).toByte() }.toByteArray()
            val decoder: CharsetDecoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.IGNORE)
                .onUnmappableCharacter(CodingErrorAction.IGNORE)
            return decoder.decode(ByteBuffer.wrap(bytes)).toString()
        }
    }
}
