package app.readly.tts.pocket

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Kotlin normalizer and tokenizer must give exactly what the Python originals give
 * (`normalize_fa.py` + the `sentencepiece` package) on every test sentence. The fixture
 * comes from `tools/pocket-export` (see its README).
 */
class PocketTextTest {
    private val fixtures: JSONArray by lazy {
        val text = javaClass.getResourceAsStream("/pocket/fixtures.json")!!.readBytes().toString(Charsets.UTF_8)
        JSONArray(text)
    }
    private val tokenizer: SentencePiece by lazy {
        SentencePiece.fromBytes(javaClass.getResourceAsStream("/pocket/tokenizer.model")!!.readBytes())
    }

    @Test
    fun normalizeMatchesPythonOnEverySentence() {
        val mismatches = mutableListOf<String>()
        for (i in 0 until fixtures.length()) {
            val row = fixtures.getJSONObject(i)
            val got = NormalizeFa.normalize(row.getString("text"))
            val want = row.getString("normalized")
            if (got != want) mismatches += "${row.getString("text")}\n  want: $want\n  got:  $got"
        }
        assertEquals("normalizer differs from Python:\n" + mismatches.joinToString("\n"), 0, mismatches.size)
    }

    @Test
    fun tokenizerMatchesPythonOnEverySentence() {
        assertEquals(4000, tokenizer.vocabSize)
        val mismatches = mutableListOf<String>()
        for (i in 0 until fixtures.length()) {
            val row = fixtures.getJSONObject(i)
            val ids = row.getJSONArray("ids")
            val want = IntArray(ids.length()) { ids.getInt(it) }.toList()
            val got = tokenizer.encode(row.getString("normalized")).toList()
            if (got != want) mismatches += "${row.getString("normalized")}\n  want: $want\n  got:  $got"
        }
        assertEquals("tokenizer differs from Python:\n" + mismatches.joinToString("\n"), 0, mismatches.size)
    }

    @Test
    fun highestScoreMergeWinsOverLongerPiece() {
        val toy = SentencePiece(
            scores = floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 9f),
            ids = mapOf("a" to 1, "b" to 2, "c" to 3, "d" to 4, "abcd" to 5, "bc" to 6),
            byteIds = emptyMap(),
            addDummyPrefix = false,
        )
        // "bc" (score 9) merges first, so the longer "abcd" (score 1) never forms.
        assertEquals(listOf(1, 6, 4), toy.encode("abcd").toList())
        assertEquals(listOf(1, 0), toy.encode("ax").toList())
    }

    @Test
    fun numbersReadAsPersianWords() {
        assertEquals("هزار و چهارصد و دو", NormalizeFa.numberToWords(1402))
        assertEquals("صفر", NormalizeFa.numberToWords(0))
        assertEquals("دو میلیون و سیصد هزار", NormalizeFa.numberToWords(2_300_000))
    }
}
