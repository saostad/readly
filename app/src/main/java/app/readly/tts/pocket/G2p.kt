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
 * (tiny ByT5) as encoder + decoder ONNX with no KV cache. One cleaned
 * Persian sentence goes in, one phoneme string comes out, via the same beam
 * search `transformers` runs for `generate(num_beams=5, max_length=512,
 * early_stopping=True)` (length penalty 1.0): the numpy version in
 * `tools/pocket-export/export_g2p.py` is the middle port, and all three
 * agree byte for byte on every golden sentence.
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
        val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6)
        encoder = openSession(encoderBytes, threads)
        decoder = try {
            openSession(decoderBytes, threads)
        } catch (e: Exception) {
            encoder.close()
            throw e
        }
    }

    private fun openSession(bytes: ByteArray, threads: Int): OrtSession {
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(threads)
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
            val hidden = runEncoder(ids)
            val best = search(hidden)
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

    private fun runEncoder(ids: LongArray): FloatArray {
        val input = longTensor(ids, longArrayOf(1, ids.size.toLong()))
        try {
            encoder.run(mapOf("input_ids" to input)).use { result ->
                return floats(result, "last_hidden_state")
            }
        } finally {
            input.close()
        }
    }

    private fun search(hidden: FloatArray): List<Long> {
        val encLen = hidden.size / D_MODEL
        // Every beam reads the same encoder states; repeat once up front.
        val enc5 = FloatArray(hidden.size * NUM_BEAMS) { hidden[it % hidden.size] }
        val encTensor = floatTensor(enc5, longArrayOf(NUM_BEAMS.toLong(), encLen.toLong(), D_MODEL.toLong()))
        try {
            val running = Array(NUM_BEAMS) { mutableListOf(PAD_ID) }
            val runningScores = DoubleArray(NUM_BEAMS) { if (it == 0) 0.0 else -1e9 }
            val finalized = Array(NUM_BEAMS) { Triple(listOf(PAD_ID), -1e9, false) }
            var curLen = 1
            var heuristicOk = true
            while (true) {
                val batch = LongArray(NUM_BEAMS * curLen) { running[it / curLen][it % curLen] }
                val input = longTensor(batch, longArrayOf(NUM_BEAMS.toLong(), curLen.toLong()))
                val logits = try {
                    decoder.run(mapOf("decoder_input_ids" to input, "encoder_hidden_states" to encTensor))
                        .use { result -> floats(result, "logits") }
                } finally {
                    input.close()
                }
                // Log-softmax of each beam's last position, plus its score.
                val flat = DoubleArray(NUM_BEAMS * VOCAB_SIZE)
                for (b in 0 until NUM_BEAMS) {
                    val base = (b * curLen + curLen - 1) * VOCAB_SIZE
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
                val topkSeqs = topk.map { running[it / VOCAB_SIZE] + (it % VOCAB_SIZE).toLong() }
                val hitsStop = topkSeqs.map { it.last() == EOS_ID || it.size >= MAX_LENGTH }
                // Running beams for the next step: best non-finished top-K.
                val masked = topkScores.mapIndexed { i, s -> if (hitsStop[i]) s - 1e9 else s }
                val next = masked.indices.sortedWith(compareBy({ -masked[it] }, { it })).take(NUM_BEAMS)
                for (b in 0 until NUM_BEAMS) {
                    running[b] = topkSeqs[next[b]].toMutableList()
                    runningScores[b] = masked[next[b]]
                }
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
                for (b in 0 until NUM_BEAMS) finalized[b] = merged[best[b]]
                curLen += 1
                // Early-stop heuristic: the best running score, penalized at
                // the hypothetical best length, can still beat the worst
                // finished beam.
                val hypoLen = (curLen - 1).toDouble().pow(LENGTH_PENALTY)
                val bestRunning = runningScores[0] / hypoLen
                val worstDone = finalized.filter { it.third }.minOfOrNull { it.second } ?: -1e9
                heuristicOk = heuristicOk && bestRunning > worstDone
                val openBeam = !finalized.all { it.third }
                if (!(heuristicOk && openBeam && !hitsStop.all { it })) break
            }
            val best = finalized.maxByOrNull { it.second }!!.first
            val eosAt = best.indexOf(EOS_ID)
            val trimmed = if (eosAt < 0) best else best.subList(0, eosAt)
            return trimmed.filter { it != PAD_ID }
        } finally {
            encTensor.close()
        }
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
        private const val D_MODEL = 512
        private const val NUM_BEAMS = 5
        private const val BEAMS_TO_KEEP = 10
        private const val MAX_LENGTH = 512
        private const val LENGTH_PENALTY = 1.0

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
