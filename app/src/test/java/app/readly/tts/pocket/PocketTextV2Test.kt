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
        // maxTokens 2 never lands between them, so the cut lands before
        // the chain instead and every chunk stays within budget.
        assertEquals(
            listOf("a", "b1 c", "d", "e f"),
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
    fun splitClausesKeepsSoftBreakMarks() {
        assertEquals(
            listOf("سلام،", "دنیا؛", "خداحافظ:", "رفت"),
            PocketTextV2.splitClauses("سلام، دنیا؛ خداحافظ: رفت"),
        )
        assertEquals(listOf("سلام دنیا"), PocketTextV2.splitClauses("سلام دنیا"))
    }

    @Test
    fun planPiecesMergesTwoShortClauses() {
        val countWords = { s: String -> s.split(" ").filter { it.isNotEmpty() }.size }
        val clauses = listOf("a b", "c d")
        assertEquals(
            listOf(TextChunk("a b c d", PocketAudio.PieceEnd.SENTENCE)),
            PocketTextV2.planPieces(clauses, countWords, 18, 8, PocketAudio.PieceEnd.SENTENCE),
        )
    }

    @Test
    fun planPiecesThreeClausesEndClauseClauseSentenceEnd() {
        val countWords = { s: String -> s.split(" ").filter { it.isNotEmpty() }.size }
        val clause = { word: String -> List(10) { word }.joinToString(" ") }
        val clauses = listOf(clause("a"), clause("b"), clause("c"))
        val pieces = PocketTextV2.planPieces(clauses, countWords, 18, 8, PocketAudio.PieceEnd.SENTENCE)
        assertEquals(3, pieces.size)
        assertEquals(
            listOf(PocketAudio.PieceEnd.CLAUSE, PocketAudio.PieceEnd.CLAUSE, PocketAudio.PieceEnd.SENTENCE),
            pieces.map { it.end },
        )
        // The sentenceEnd argument is used verbatim for the last piece.
        val piecesMid = PocketTextV2.planPieces(clauses, countWords, 18, 8, PocketAudio.PieceEnd.MID)
        assertEquals(PocketAudio.PieceEnd.MID, piecesMid.last().end)
    }

    @Test
    fun planPiecesLongClauseBetweenShortOnes() {
        val countWords = { s: String -> s.split(" ").filter { it.isNotEmpty() }.size }
        val long = List(30) { "w" }.joinToString(" ")
        val clauses = listOf("a b", long, "c d")
        val pieces = PocketTextV2.planPieces(clauses, countWords, 18, 8, PocketAudio.PieceEnd.SENTENCE)
        val ends = pieces.map { it.end }
        assertEquals("a b", pieces[0].text)
        assertEquals("c d", pieces.last().text)
        // Clause edges end Clause, the inside of the split clause ends Mid.
        assertEquals(PocketAudio.PieceEnd.CLAUSE, ends[0])
        assertTrue("expected a Mid inside the split clause: $ends", PocketAudio.PieceEnd.MID in ends.subList(1, ends.size - 1))
        assertEquals(
            "last sub-piece of the long clause ends at its clause edge: $ends",
            PocketAudio.PieceEnd.CLAUSE,
            ends[ends.size - 2],
        )
        assertEquals(PocketAudio.PieceEnd.SENTENCE, ends[ends.size - 1])
        for (piece in pieces) {
            assertTrue("${piece.text}", countWords(piece.text) <= 18)
        }
    }

    @Test
    fun planPiecesEmptyInputGivesNoPieces() {
        val countWords = { s: String -> s.split(" ").filter { it.isNotEmpty() }.size }
        assertTrue(
            PocketTextV2.planPieces(emptyList(), countWords, 18, 8, PocketAudio.PieceEnd.SENTENCE).isEmpty(),
        )
    }

    @Test
    fun planV2Chunks_twoClauseSentenceEndsClauseThenSentence() {
        val countWords = { s: String -> s.split(" ").filter { it.isNotEmpty() }.size }
        val phonemise = { window: String ->
            PocketTextV2.faWords(window).mapIndexed { i, _ -> listOf("m", "n", "o", "p")[i] }.joinToString(" ")
        }
        val plan = PocketTextV2.planV2Chunks(
            "سلام دنیا، خداحافظ رفت.",
            PocketTuning(maxTokens = 2, minTokens = 1),
            phonemise,
            countWords,
        )
        assertEquals(
            listOf(
                TextChunk("m n", PocketAudio.PieceEnd.CLAUSE),
                TextChunk("o p", PocketAudio.PieceEnd.SENTENCE),
            ),
            plan.chunks,
        )
        assertTrue(plan.blanks.isEmpty())
        assertFalse(plan.alignmentFallback)
    }

    @Test
    fun planV2Chunks_textEndingInClauseMarkEndsClause() {
        val countWords = { s: String -> s.split(" ").filter { it.isNotEmpty() }.size }
        val phonemise = { window: String ->
            PocketTextV2.faWords(window).mapIndexed { i, _ -> listOf("m", "n", "o", "p")[i] }.joinToString(" ")
        }
        val plan = PocketTextV2.planV2Chunks(
            "سلام دنیا، خداحافظ رفت،",
            PocketTuning(maxTokens = 2, minTokens = 1),
            phonemise,
            countWords,
        )
        assertEquals(
            listOf(PocketAudio.PieceEnd.CLAUSE, PocketAudio.PieceEnd.CLAUSE),
            plan.chunks.map { it.end },
        )
        assertFalse(plan.alignmentFallback)
    }

    @Test
    fun planV2Chunks_mismatchedWindowFallsBackToOneClause() {
        val countWords = { s: String -> s.split(" ").filter { it.isNotEmpty() }.size }
        val plan = PocketTextV2.planV2Chunks(
            "سلام دنیا، خداحافظ رفت.",
            PocketTuning(),
            { "onlyone" },
            countWords,
        )
        assertTrue(plan.alignmentFallback)
        assertEquals(
            listOf(TextChunk("onlyone", PocketAudio.PieceEnd.SENTENCE)),
            plan.chunks,
        )
    }

    @Test
    fun chunkBoundariesNeverSplitAnEzafeChain() {
        // Word counter; every word but the last carries an ezafe mark, so no
        // boundary may land inside the chain whatever the budget says.
        val countWords = { s: String -> s.split(" ").filter { it.isNotEmpty() }.size }
        val chunks = PocketTextV2.splitChunks("ketAbe1 jadide1 duste1 man", countWords, maxTokens = 3, minTokens = 2)
        assertEquals(listOf("ketAbe1 jadide1 duste1 man"), chunks)
    }

    @Test
    fun sensibleCutPrefersBeforeVa() {
        val countWords = { s: String -> s.split(" ").filter { it.isNotEmpty() }.size }
        // `miravad` ends with `ad`, so it looks like a finite verb and a
        // cut before the following `va` is preferred.
        assertEquals(
            listOf("a b miravad", "va d e f"),
            PocketTextV2.splitChunks("a b miravad va d e f", countWords, maxTokens = 4, minTokens = 2),
        )
    }

    @Test
    fun sensibleCutBeforeVaAfterNonVerbGetsNoPreference() {
        val countWords = { s: String -> s.split(" ").filter { it.isNotEmpty() }.size }
        // `SomAli` is not a finite verb, so the cut before `va` gets no
        // preference and the balanced cut (4/4, not 2/6) wins.
        assertEquals(
            listOf("a SomAli va qarbi", "d e f g"),
            PocketTextV2.splitChunks("a SomAli va qarbi d e f g", countWords, maxTokens = 6, minTokens = 2),
        )
    }

    @Test
    fun sensibleCutNeverAfterDarOrBeforeRa() {
        val countWords = { s: String -> s.split(" ").filter { it.isNotEmpty() }.size }
        val chunks = PocketTextV2.splitChunks("a dar b c d e f g", countWords, maxTokens = 3, minTokens = 2)
        for (chunk in chunks.dropLast(1)) {
            assertTrue("$chunks", chunk.split(" ").last() != "dar")
        }
        val chunks2 = PocketTextV2.splitChunks("a b rA c d e f g", countWords, maxTokens = 3, minTokens = 2)
        for (chunk in chunks2.drop(1)) {
            assertTrue("$chunks2", chunk.split(" ").first() != "rA")
        }
    }

    @Test
    fun sensibleCutNeverAfterVowelYe() {
        val countWords = { s: String -> s.split(" ").filter { it.isNotEmpty() }.size }
        // `?ostAnhAye` carries no ezafe mark, but its vowel + `ye` ending
        // still binds it to `SomAli`, so no piece may end with it.
        for (word in listOf("?ostAnhAye", "hameye", "sedAye", "buye")) {
            assertFalse(word, PocketTextV2.isAllowedCut(word, "SomAli"))
        }
        val chunks = PocketTextV2.splitChunks("a b ?ostAnhAye SomAli c d e f", countWords, maxTokens = 4, minTokens = 2)
        for (chunk in chunks.dropLast(1)) {
            assertTrue("$chunks", chunk.split(" ").last() != "?ostAnhAye")
        }
    }

    @Test
    fun sensibleCutNeverAfterANumberWord() {
        val countWords = { s: String -> s.split(" ").filter { it.isNotEmpty() }.size }
        val chunks = PocketTextV2.splitChunks(
            "jalase sA?ate haSt ?o si daqiqe sobh bargozAr miSavad", countWords, maxTokens = 5, minTokens = 2,
        )
        for (chunk in chunks.dropLast(1)) {
            val last = chunk.split(" ").last()
            assertTrue("$chunks", last != "si")
            assertTrue("$chunks", last != "haSt")
            assertTrue("$chunks", last != "?o")
        }
        // No allowed cut exists inside a run of number words, so the
        // `pack` fallback must still return every word once, in order.
        val text = "yek do se CahAr panj SeS haft haSt noh dah"
        val chunks2 = PocketTextV2.splitChunks(text, countWords, maxTokens = 4, minTokens = 2)
        val words = chunks2.flatMap { it.split(" ") }
        assertEquals(text.split(" "), words)
    }

    @Test
    fun sensibleCutNeverBeforeAVerbOrAfterBayad() {
        val countWords = { s: String -> s.split(" ").filter { it.isNotEmpty() }.size }
        assertEquals(
            listOf("fAyle ?eksel rA ?eksport kon", "va dar gugel derAyv ?Aplod kon"),
            PocketTextV2.splitChunks(
                "fAyle ?eksel rA ?eksport kon va dar gugel derAyv ?Aplod kon",
                countWords, maxTokens = 6, minTokens = 2,
            ),
        )
        val chunks = PocketTextV2.splitChunks(
            "lotfan fAyl rA be ?imeyle1 man beferestid va ?az ?estefAde konid",
            countWords, maxTokens = 8, minTokens = 2,
        )
        for (chunk in chunks.drop(1)) {
            val first = chunk.split(" ").first()
            assertTrue("$chunks", first != "beferestid")
            assertTrue("$chunks", first != "konid")
        }
        val chunks2 = PocketTextV2.splitChunks("a b bAyad c d e f g", countWords, maxTokens = 4, minTokens = 2)
        for (chunk in chunks2.dropLast(1)) {
            assertTrue("$chunks2", chunk.split(" ").last() != "bAyad")
        }
    }

    @Test
    fun sensibleCutFiftyWordsWithinBudgetAndMinimum() {
        val countWords = { s: String -> s.split(" ").filter { it.isNotEmpty() }.size }
        val text = List(50) { "word" }.joinToString(" ")
        val chunks = PocketTextV2.splitChunks(text, countWords, maxTokens = 18, minTokens = 8)
        assertTrue("$chunks", chunks.size >= 3)
        for (chunk in chunks) {
            val count = countWords(chunk)
            assertTrue(chunk, count <= 18)
            assertTrue(chunk, count >= 8)
        }
    }

    @Test
    fun shortTailMovesWordsBackUnlessAfterABreak() {
        val countWords = { s: String -> s.split(" ").filter { it.isNotEmpty() }.size }
        // Moving "c" back would cut after "b1", which carries an ezafe
        // mark, so that cut is not allowed and the short tail stays put.
        assertEquals(
            listOf("a1 b1 c", "d"),
            PocketTextV2.splitChunks("a1 b1 c d", countWords, maxTokens = 3, minTokens = 2),
        )
        // After a real break the short tail stays put.
        assertEquals(
            listOf("w1 w2 w3.", "w4"),
            PocketTextV2.splitChunks("w1 w2 w3. w4", countWords, maxTokens = 3, minTokens = 2),
        )
    }

    @Test
    fun g2pWindowsShortSentenceIsOneWindow() {
        val sentence = "ک".repeat(100)
        assertEquals(100, sentence.length)
        assertEquals(listOf(sentence), PocketTextV2.g2pWindows(sentence, 120))
    }

    @Test
    fun g2pWindowsLongClauseSplitsBeforeVaRanandegan() {
        var sentence = "سازمان هواشناسی اعلام کرد که از اواخر هفته\u200C جاری بارش باران و برف در بیشتر استان\u200Cهای شمالی و غربی کشور آغاز می\u200Cشود و رانندگان باید در جاده\u200Cهای کوهستانی با احتیاط بیشتری رانندگی کنند."
        // The sentence above uses هفتهٔ / استان‌های / می‌شود / جاده‌های with
        // ZWNJ; rebuild it exactly as given so the char count matches.
        sentence = sentence.replace("هفته\u200C جاری", "هفته\u0654 جاری")
        assertEquals(182, sentence.length)
        val windows = PocketTextV2.g2pWindows(sentence, 120)
        assertEquals("$windows", 2, windows.size)
        for (window in windows) {
            assertTrue(window, window.length <= 120)
        }
        assertTrue(windows[1], windows[1].startsWith("و رانندگان"))
        val joined = windows.flatMap { it.split(Regex("\\s+")).filter { w -> w.isNotEmpty() } }
        assertEquals(sentence.split(Regex("\\s+")).filter { it.isNotEmpty() }, joined)
    }

    @Test
    fun g2pWindowsGroupsClausesGreedily() {
        val sentence = "الف ب، ج د، ه و، ز ح"
        assertEquals(
            listOf("الف ب، ج د،", "ه و، ز ح"),
            PocketTextV2.g2pWindows(sentence, 12),
        )
    }

    @Test
    fun dedupeRepeatsDropsRepeatsAndHalvesDoubling() {
        assertEquals(
            listOf("salAm"),
            PocketTextV2.dedupeRepeats(listOf("سلام،"), listOf("salAm", "salAm")),
        )
        assertEquals(
            listOf("na"),
            PocketTextV2.dedupeRepeats(listOf("نه!"), listOf("na", "na")),
        )
        assertEquals(
            listOf("tam"),
            PocketTextV2.dedupeRepeats(listOf("تم."), listOf("tamtam")),
        )
        assertEquals(
            listOf("bAbA"),
            PocketTextV2.dedupeRepeats(listOf("بابا"), listOf("bAbA")),
        )
    }

    @Test
    fun alignWordsCounts() {
        assertEquals(
            listOf(1, 1),
            PocketTextV2.alignWords(listOf("سلام", "دنیا"), listOf("salAm", "donyA")),
        )
        assertEquals(
            listOf(1, 2, 1, 1),
            PocketTextV2.alignWords(
                listOf("چرا", "این\u200Cقدر", "دیر", "آمدی"),
                listOf("CerA", "?in", "qadr", "dir", "?Amadi"),
            ),
        )
        assertEquals(
            listOf(2, 1),
            PocketTextV2.alignWords(
                listOf("لپ\u200Cتاپ", "می\u200Cخرم"),
                listOf("lap", "tAp", "mixaram"),
            ),
        )
        assertEquals(
            listOf(1, 1),
            PocketTextV2.alignWords(
                listOf("لپ\u200Cتاپ", "می\u200Cخرم"),
                listOf("laptAp", "mixaram"),
            ),
        )
        assertEquals(
            null,
            PocketTextV2.alignWords(listOf("سلام", "دنیا"), listOf("salAm", "donyA", "extra")),
        )
    }

    @Test
    fun phonemeClausesSplitsByAlignment() {
        val clauses = listOf(
            "بعد از باران،",
            "کفش\u200Cهایم پر از گل شد.",
        )
        val ph = listOf("ba?d", "?az", "bArAn", "kafSAyam", "por", "?az", "gel", "Sod")
        assertEquals(
            listOf("ba?d ?az bArAn", "kafSAyam por ?az gel Sod"),
            PocketTextV2.phonemeClauses(clauses, ph),
        )
    }

    @Test
    fun applyOverridesReplacesKnownWordsAndKeepsEzafe() {
        val lookup: (String) -> String? = { word ->
            when (word) {
                "اکسل" -> "?eksel"
                "گوگل" -> "gugel"
                "دیتا" -> "deytA"
                "لپ\u200Cتاپ" -> "laptAp"
                else -> null
            }
        }
        // A known word is replaced; its neighbour's ezafe is untouched.
        val first = PocketTextV2.applyOverrides(
            listOf("فایل", "اکسل", "را"),
            listOf("fAyle1", "?exkel", "rA"),
            listOf(1, 1, 1),
            lookup,
        )
        assertEquals(listOf("fAyle1", "?eksel", "rA"), first.phonemeWords)
        assertEquals(listOf(1, 1, 1), first.counts)
        // An ezafe on the replaced word is kept, `e1` after a consonant.
        val second = PocketTextV2.applyOverrides(
            listOf("گوگل", "جدید"),
            listOf("gugle1", "jadid"),
            listOf(1, 1),
            lookup,
        )
        assertEquals(listOf("gugele1", "jadid"), second.phonemeWords)
        // A vowel-final override gets `ye1`.
        val third = PocketTextV2.applyOverrides(
            listOf("دیتا", "جدید"),
            listOf("deytA1", "jadid"),
            listOf(1, 1),
            lookup,
        )
        assertEquals(listOf("deytAye1", "jadid"), third.phonemeWords)
        // A ZWNJ word the model split becomes its single override word.
        val fourth = PocketTextV2.applyOverrides(
            listOf("لپ\u200Cتاپ"),
            listOf("lap", "tAp"),
            listOf(2),
            lookup,
        )
        assertEquals(listOf("laptAp"), fourth.phonemeWords)
        assertEquals(listOf(1), fourth.counts)
        // Punctuation on the Persian word still matches; unknown words pass through.
        val fifth = PocketTextV2.applyOverrides(listOf("اکسل،"), listOf("?exkel"), listOf(1), lookup)
        assertEquals(listOf("?eksel"), fifth.phonemeWords)
        val sixth = PocketTextV2.applyOverrides(listOf("سلام"), listOf("salAm"), listOf(1), lookup)
        assertEquals(listOf("salAm"), sixth.phonemeWords)
        assertEquals(listOf(1), sixth.counts)
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
