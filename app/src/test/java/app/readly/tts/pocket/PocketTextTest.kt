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

    @Test
    fun pythonWhitespaceSetMatchesJavaClass() {
        // Output of: python3 -c "import re; print([c for c in range(0x110000)
        // if re.match(r'\s', chr(c))])" (Python 3.12): White_Space plus U+001C..U+001F.
        val pythonWhitespace = listOf(
            0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20,
            0x85, 0xA0, 0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004,
            0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A, 0x2028, 0x2029,
            0x202F, 0x205F, 0x3000,
        )
        val pattern = java.util.regex.Pattern.compile(NormalizeFa.UNICODE_WS)
        val javaMatches = (0 until 0x110000)
            .filter { it !in 0xD800..0xDFFF }
            .filter { pattern.matcher(String(Character.toChars(it))).matches() }
        assertEquals(pythonWhitespace, javaMatches)
    }

    @Test
    fun noUnicodeFlagInMainSources() {
        // Android's ICU regex throws PatternSyntaxException on (?U) at class-init time
        // (this crashed NormalizeFa on device); every Unicode class in main sources must
        // be spelled out instead. This test fails if (?U) ever comes back.
        val userDir = checkNotNull(System.getProperty("user.dir")) { "user.dir is not set" }
        val roots = generateSequence(java.io.File(userDir)) { it.parentFile }
            .flatMap { sequenceOf(java.io.File(it, "src/main/java"), java.io.File(it, "app/src/main/java")) }
            .firstOrNull { it.isDirectory }
            ?: throw AssertionError("cannot find src/main/java from user.dir " + System.getProperty("user.dir"))
        val hits = roots.walkTopDown().filter { it.extension == "kt" }
            .filter { "(?U)" in it.readText() }.map { it.relativeTo(roots).path }.toList()
        assertEquals("(?U) is not supported by Android ICU regex: $hits", emptyList<String>(), hits)
    }
}
