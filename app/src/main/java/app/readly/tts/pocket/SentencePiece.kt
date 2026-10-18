package app.readly.tts.pocket

import java.io.IOException

/**
 * Minimal SentencePiece unigram encoder for the tokenizer baked into the Pocket model
 * (`pocket_tts_tokenizer` metadata). Reads the `ModelProto` bytes directly and runs the
 * same best-score (Viterbi) segmentation as SentencePiece, with byte fallback.
 *
 * Text must already be normalized ([NormalizeFa]); this does not apply SentencePiece's
 * own NFKC character map. Checked against the Python `sentencepiece` package on every
 * test sentence (`src/test/resources/pocket/fixtures.json`).
 */
class SentencePiece private constructor(
    private val scores: FloatArray,
    private val ids: Map<String, Int>,
    private val byteIds: Map<Int, Int>,
    private val maxPieceLen: Int,
    private val unkScore: Double,
    private val addDummyPrefix: Boolean,
) {
    val vocabSize: Int get() = scores.size

    fun encode(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val normalized = buildString(text.length + 1) {
            if (addDummyPrefix) append(SPACE)
            for (c in text) append(if (c == ' ') SPACE else c)
        }
        // Work on code points so a piece never splits a surrogate pair.
        val cps = normalized.codePoints().toArray()
        val n = cps.size
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val back = IntArray(n + 1) { -1 }
        best[0] = 0.0
        for (end in 1..n) {
            val earliest = maxOf(0, end - maxPieceLen)
            for (start in earliest until end) {
                if (best[start] == Double.NEGATIVE_INFINITY) continue
                val id = ids[String(cps, start, end - start)] ?: continue
                val score = best[start] + scores[id]
                if (score > best[end]) {
                    best[end] = score
                    back[end] = start
                }
            }
            if (best[end] == Double.NEGATIVE_INFINITY && best[end - 1] != Double.NEGATIVE_INFINITY) {
                best[end] = best[end - 1] + unkScore
                back[end] = end - 1
            }
        }
        val spans = ArrayList<IntArray>()
        var end = n
        while (end > 0) {
            val start = back[end]
            if (start < 0) break
            spans += intArrayOf(start, end)
            end = start
        }
        spans.reverse()
        val out = ArrayList<Int>(spans.size)
        for ((start, stop) in spans.map { it[0] to it[1] }) {
            val piece = String(cps, start, stop - start)
            val id = ids[piece]
            if (id != null) {
                out += id
                continue
            }
            val bytes = piece.toByteArray(Charsets.UTF_8)
            if (bytes.all { (it.toInt() and 0xff) in byteIds }) {
                for (b in bytes) out += byteIds.getValue(b.toInt() and 0xff)
            } else {
                out += UNK_ID
            }
        }
        return out.toIntArray()
    }

    companion object {
        private const val SPACE = '▁'
        private const val UNK_PENALTY = 10.0
        private const val UNK_ID = 0
        private const val NORMAL = 1
        private const val UNKNOWN = 2
        private const val USER_DEFINED = 4
        private const val BYTE = 6

        /** Parses a serialized SentencePiece `ModelProto`. */
        fun fromBytes(bytes: ByteArray): SentencePiece {
            val reader = ProtoReader(bytes)
            val pieces = ArrayList<Triple<String, Float, Int>>()
            var addDummyPrefix = true
            while (!reader.done) {
                val tag = reader.varint()
                val field = (tag ushr 3).toInt()
                val wire = (tag and 7).toInt()
                when {
                    field == 1 && wire == 2 -> pieces += parsePiece(reader.bytesField())
                    field == 4 && wire == 2 -> addDummyPrefix = parseAddDummyPrefix(reader.bytesField())
                    else -> reader.skip(wire)
                }
            }
            if (pieces.isEmpty()) throw IOException("Couldn't read the voice tokenizer: it has no pieces")
            val ids = HashMap<String, Int>()
            val byteIds = HashMap<Int, Int>()
            var minScore = Double.POSITIVE_INFINITY
            var longest = 1
            pieces.forEachIndexed { id, (text, score, kind) ->
                when (kind) {
                    NORMAL, USER_DEFINED -> {
                        ids[text] = id
                        longest = maxOf(longest, text.codePointCount(0, text.length))
                        minScore = minOf(minScore, score.toDouble())
                    }
                    BYTE -> text.substring(3, 5).toIntOrNull(16)?.let { byteIds[it] = id }
                    UNKNOWN -> ids.remove(text)
                }
            }
            return SentencePiece(
                scores = FloatArray(pieces.size) { pieces[it].second },
                ids = ids,
                byteIds = byteIds,
                maxPieceLen = longest,
                unkScore = minScore - UNK_PENALTY,
                addDummyPrefix = addDummyPrefix,
            )
        }

        private fun parsePiece(bytes: ByteArray): Triple<String, Float, Int> {
            val reader = ProtoReader(bytes)
            var text = ""
            var score = 0f
            var kind = NORMAL
            while (!reader.done) {
                val tag = reader.varint()
                val field = (tag ushr 3).toInt()
                val wire = (tag and 7).toInt()
                when {
                    field == 1 && wire == 2 -> text = String(reader.bytesField(), Charsets.UTF_8)
                    field == 2 && wire == 5 -> score = reader.float()
                    field == 3 && wire == 0 -> kind = reader.varint().toInt()
                    else -> reader.skip(wire)
                }
            }
            return Triple(text, score, kind)
        }

        private fun parseAddDummyPrefix(bytes: ByteArray): Boolean {
            val reader = ProtoReader(bytes)
            var add = true
            while (!reader.done) {
                val tag = reader.varint()
                if (tag ushr 3 == 3L && tag and 7 == 0L) add = reader.varint() != 0L else reader.skip((tag and 7).toInt())
            }
            return add
        }
    }

    private class ProtoReader(private val bytes: ByteArray) {
        private var offset = 0
        val done: Boolean get() = offset >= bytes.size

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (offset >= bytes.size) throw IOException("Couldn't read the voice tokenizer: truncated varint")
                val b = bytes[offset++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift >= 64) throw IOException("Couldn't read the voice tokenizer: varint too long")
            }
        }

        fun bytesField(): ByteArray {
            val len = varint().toInt()
            if (len < 0 || offset + len > bytes.size) {
                throw IOException("Couldn't read the voice tokenizer: truncated field")
            }
            return bytes.copyOfRange(offset, offset + len).also { offset += len }
        }

        fun float(): Float {
            if (offset + 4 > bytes.size) throw IOException("Couldn't read the voice tokenizer: truncated float")
            val bits = (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                ((bytes[offset + 3].toInt() and 0xff) shl 24)
            offset += 4
            return Float.fromBits(bits)
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> offset += 8
                2 -> bytesField()
                5 -> offset += 4
                else -> throw IOException("Couldn't read the voice tokenizer: unsupported wire type $wire")
            }
            if (offset > bytes.size) throw IOException("Couldn't read the voice tokenizer: skip past end")
        }
    }
}
