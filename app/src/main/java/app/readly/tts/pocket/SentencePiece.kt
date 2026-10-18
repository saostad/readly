package app.readly.tts.pocket

import java.io.IOException

/**
 * Minimal SentencePiece BPE encoder for the tokenizer baked into the Pocket model
 * (`pocket_tts_tokenizer` metadata). Reads the `ModelProto` bytes directly and runs the
 * same greedy highest-score merges as SentencePiece, with byte fallback.
 *
 * Text must already be normalized ([NormalizeFa]); this does not apply SentencePiece's
 * own NFKC character map. Checked against the Python `sentencepiece` package on every
 * test sentence (`src/test/resources/pocket/fixtures.json`).
 */
class SentencePiece internal constructor(
    private val scores: FloatArray,
    private val ids: Map<String, Int>,
    private val byteIds: Map<Int, Int>,
    private val addDummyPrefix: Boolean,
) {
    val vocabSize: Int get() = scores.size

    fun encode(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val normalized = buildString(text.length + 1) {
            if (addDummyPrefix) append(SPACE)
            // The model's normalizer maps ZWNJ to a word boundary too: no vocab piece
            // contains U+200C, and Python splits half-spaces ([▁می][▁شود]).
            for (c in text) append(if (c == ' ' || c == ZWNJ) SPACE else c)
        }
        // One symbol per code point so a merge never splits a surrogate pair.
        val symbols = ArrayList<String>(normalized.length)
        var offset = 0
        while (offset < normalized.length) {
            val cp = normalized.codePointAt(offset)
            symbols += String(Character.toChars(cp))
            offset += Character.charCount(cp)
        }
        // Greedy BPE: each round merges the adjacent pair whose concatenation is a
        // NORMAL or USER_DEFINED piece with the highest score (ties: leftmost).
        while (symbols.size > 1) {
            var bestScore = Float.NEGATIVE_INFINITY
            var bestPos = -1
            for (pos in 0 until symbols.size - 1) {
                val id = ids[symbols[pos] + symbols[pos + 1]] ?: continue
                val score = scores[id]
                if (score > bestScore) {
                    bestScore = score
                    bestPos = pos
                }
            }
            if (bestPos < 0) break
            symbols[bestPos] = symbols[bestPos] + symbols[bestPos + 1]
            symbols.removeAt(bestPos + 1)
        }
        val out = ArrayList<Int>(symbols.size)
        for (symbol in symbols) {
            val id = ids[symbol]
            if (id != null) {
                out += id
                continue
            }
            val bytes = symbol.toByteArray(Charsets.UTF_8)
            if (bytes.isNotEmpty() && bytes.all { (it.toInt() and 0xff) in byteIds }) {
                for (b in bytes) out += byteIds.getValue(b.toInt() and 0xff)
            } else {
                out += UNK_ID
            }
        }
        return out.toIntArray()
    }

    companion object {
        private const val SPACE = '▁'
        private const val ZWNJ = '\u200C'
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
            pieces.forEachIndexed { id, (text, _, kind) ->
                when (kind) {
                    NORMAL, USER_DEFINED -> ids[text] = id
                    BYTE -> text.substring(3, 5).toIntOrNull(16)?.let { byteIds[it] = id }
                    UNKNOWN -> ids.remove(text)
                }
            }
            return SentencePiece(
                scores = FloatArray(pieces.size) { pieces[it].second },
                ids = ids,
                byteIds = byteIds,
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
