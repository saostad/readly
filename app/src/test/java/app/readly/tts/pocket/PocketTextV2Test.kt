package app.readly.tts.pocket

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The Kotlin v2 front end must give exactly what the reference gives
 * (`normalize_fa_v2.py` + `synthesize.py` + `pocket_helpers.py`) on every
 * golden row (`src/test/resources/pocket/v2-golden.jsonl`).
 */
class PocketTextV2Test {
    private data class GoldenSentence(val clean: String, val g2pRaw: String, val phonemes: String)
    private data class GoldenRow(val text: String, val normalized: String, val sentences: List<GoldenSentence>)

    private val golden: List<GoldenRow> by lazy {
        val stream = javaClass.getResourceAsStream("/pocket/v2-golden.jsonl")
            ?: throw IllegalStateException("test resource /pocket/v2-golden.jsonl is missing")
        stream.bufferedReader(Charsets.UTF_8).lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val row = JSONObject(line)
                val sentences = row.getJSONArray("sentences")
                GoldenRow(
                    text = row.getString("text"),
                    normalized = row.getString("normalized"),
                    sentences = List(sentences.length()) { i ->
                        val s = sentences.getJSONObject(i)
                        GoldenSentence(s.getString("clean"), s.getString("g2p_raw"), s.getString("phonemes"))
                    },
                )
            }.toList()
    }

    @Test
    fun goldenHasAllSentences() {
        assertEquals(53, golden.size)
        assertTrue(golden.all { it.sentences.isNotEmpty() })
    }

    @Test
    fun normalizeForModelMatchesGoldenOnEveryRow() {
        val mismatches = mutableListOf<String>()
        for (row in golden) {
            val got = PocketTextV2.normalizeForModel(row.text)
            if (got != row.normalized) mismatches += "${row.text}\n  want: ${row.normalized}\n  got:  $got"
        }
        assertEquals("normalizeForModel differs from Python:\n" + mismatches.joinToString("\n"), 0, mismatches.size)
    }

    @Test
    fun splitCleanAndTranslateMatchGoldenOnEveryRow() {
        val mismatches = mutableListOf<String>()
        for (row in golden) {
            val gotSentences = PocketTextV2.splitSentences(row.normalized)
            val gotCleans = gotSentences.map { PocketTextV2.cleanSentenceForG2p(it) }
            val wantCleans = row.sentences.map { it.clean }
            if (gotCleans != wantCleans) {
                mismatches += "${row.normalized}\n  want: $wantCleans\n  got:  $gotCleans"
                continue
            }
            for (sentence in row.sentences) {
                val got = PocketTextV2.translatePhonemes(sentence.g2pRaw)
                if (got != sentence.phonemes) {
                    mismatches += "${sentence.g2pRaw}\n  want: ${sentence.phonemes}\n  got:  $got"
                }
            }
        }
        assertEquals("split/clean/translate differ from Python:\n" + mismatches.joinToString("\n"), 0, mismatches.size)
    }

    @Test
    fun isPhonemicDetectsScript() {
        assertTrue(PocketTextV2.isPhonemic("ketabe1 jadide1 duste1 man"))
        assertTrue(PocketTextV2.isPhonemic("hello"))
        assertFalse(PocketTextV2.isPhonemic("کتاب جدید"))
        assertFalse(PocketTextV2.isPhonemic("کتاب hello")) // any Persian letter wins
        assertFalse(PocketTextV2.isPhonemic(""))
        assertFalse(PocketTextV2.isPhonemic("123 ؟!"))
    }

    @Test
    fun normalizeForModelPassesPhonemesThrough() {
        val phonemes = "ketabe1 jadide1 duste1 man"
        assertEquals(phonemes, PocketTextV2.normalizeForModel(phonemes))
    }

    @Test
    fun normalizeForModelThrowsWhenEmptied() {
        // Punctuation-only: not phonemic (no ASCII letter), and nothing
        // survives normalization. (Latin text passes through as phonemes.)
        try {
            PocketTextV2.normalizeForModel("؟؟؟")
            fail("punctuation-only input must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "message must name the failure, was: ${e.message}",
                e.message?.startsWith("normalisation emptied the text: ") == true,
            )
        }
    }

    @Test
    fun stripEzafeRemovesMarks() {
        assertEquals("ketabe jadide duste man", PocketTextV2.stripEzafe("ketabe1 jadide1 duste1 man"))
        assertEquals("abc", PocketTextV2.stripEzafe("abc"))
    }

    @Test
    fun splitChunksRespectsBudgetAndEzafeBinding() {
        // Word-count tokens (expectations verified against synthesize.py).
        val countWords = { s: String -> s.split(" ").size }
        assertEquals(
            listOf("a b1 c", "d e f"),
            PocketTextV2.splitChunks("a b1 c d e f", countWords, maxTokens = 3, minTokens = 1),
        )
        // The ezafe mark on "b1" binds it to "c": the boundary that fits at
        // maxTokens 2 never lands between them ("a b1 c" runs over budget).
        assertEquals(
            listOf("a b1 c", "d e", "f"),
            PocketTextV2.splitChunks("a b1 c d e f", countWords, maxTokens = 2, minTokens = 1),
        )
        assertEquals(
            listOf("a b c"),
            PocketTextV2.splitChunks("a b c", countWords, maxTokens = 18, minTokens = 8),
        )
        assertEquals(
            emptyList<String>(),
            PocketTextV2.splitChunks("   ", countWords, maxTokens = 18, minTokens = 8),
        )
    }

    @Test
    fun splitChunksRebalancesShortTails() {
        // 7 words at a budget of 3 packs 3+3+1; the 1-word tail is under the
        // minTokens floor of 2, so one word moves back: 3+2+2.
        val countWords = { s: String -> s.split(" ").size }
        assertEquals(
            listOf("w0 w1 w2", "w3 w4", "w5 w6"),
            PocketTextV2.splitChunks(
                "w0 w1 w2 w3 w4 w5 w6", countWords, maxTokens = 3, minTokens = 2,
            ),
        )
    }
}
