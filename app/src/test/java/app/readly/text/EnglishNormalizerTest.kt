package app.readly.text

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EnglishNormalizerTest {

    private val all = NormalizeOptions(normalizeText = true, respellNames = true, dateOrder = DateOrder.MONTH_FIRST)

    private fun norm(text: String, options: NormalizeOptions = all) = EnglishNormalizer.normalize(text, options)

    @Test
    fun readsStructuredTextLikeAPerson() {
        val cases = listOf(
            "Order #4471 ships 14/03." to "Order number four four seven one ships the fourteenth of March.",
            "The parcel weighs 2.4 kg." to "The parcel weighs two point four kilograms.",
            "Henry VIII had six wives." to "Henry the eighth had six wives.",
            "The temperature is -3.5°C." to "The temperature is minus three point five degrees Celsius.",
        )
        for ((written, spoken) in cases) assertEquals("input: $written", spoken, norm(written))
    }

    @Test
    fun respelledNamesAreOneWordEach() {
        assertEquals("Bahgahn", EnglishNormalizer.joinSyllables("Bah-gahn"))
        assertEquals("Buguhvud Geetah", EnglishNormalizer.joinSyllables("Bug-uh-vud Gee-tah"))
        assertEquals("Buguhvud Geetah.", norm("Bhagavad Gita.", all.copy(normalizeText = false)))
    }

    @Test
    fun multipliersAndSizesAreSaidInWords() {
        val cases = listOf(
            "It cost about 3x what it did in 2019." to "It cost about three times what it did in twenty nineteen.",
            "Sales grew 3x." to "Sales grew three times.",
            "Sales grew 3x, then fell." to "Sales grew three times, then fell.",
            "Prices rose 3X in a year." to "Prices rose three times in a year.",
            "It's 3X cheaper." to "It's three times cheaper.",
            "That is 2.5x the price." to "That is two point five times the price.",
            "He is a 10x engineer." to "He is a ten x engineer.",
            "We saw a 3x improvement." to "We saw a three x improvement.",
            "Get the 4x4 truck." to "Get the four by four truck.",
            "Set it to 800x600." to "Set it to eight hundred by six hundred.",
            // Hex, file names and codes keep their own rules.
            "The flag is 0x1F." to "The flag is zero X one F.",
            "The image is logo@2x.png." to "The image is logo at 2x dot png.",
            "Code 7X-19." to "Code seven X nineteen.",
        )
        for ((written, spoken) in cases) assertEquals("input: $written", spoken, norm(written))
    }

    @Test
    fun busNumbersAreSaidLikePeopleSayThem() {
        assertEquals("Bus forty two stops here.", norm("Bus no. 42 stops here."))
        // Other numbers keep the word.
        assertEquals("Item number forty two.", norm("Item no. 42."))
    }

    @Test
    fun mapScalesAreRatiosNotClockTimes() {
        assertEquals("The scale is one to twenty five thousand.", norm("The scale is 1:25,000."))
        assertEquals("A one to one hundred thousand map.", norm("A 1:100000 map."))
        // Real clock times keep their own rules.
        assertEquals("Leave at one twenty five.", norm("Leave at 1:25."))
    }

    @Test
    fun oneTextReadsEveryDateInOneOrder() {
        val dayFirst = all.copy(dateOrder = DateOrder.DAY_FIRST)
        val ambiguous = "Shipped on 09/10/2026, arriving 12/10."
        assertEquals(
            "Shipped on September tenth twenty twenty six, arriving December tenth.",
            norm(ambiguous),
        )
        assertEquals(
            "Shipped on the ninth of October twenty twenty six, arriving the twelfth of October.",
            norm(ambiguous, dayFirst),
        )
        // An unambiguous date settles the order, whatever the setting.
        assertEquals("From the ninth of October to the twenty fifth of October.", norm("From 09/10 to 25/10."))
        assertEquals("From September tenth to October twenty fifth.", norm("From 09/10 to 10/25.", dayFirst))
    }

    @Test
    fun prosePassesThroughUnchanged() {
        val prose = "The old harbor town woke slowly, as if told there was no reason to hurry."
        assertEquals(prose, norm(prose))
    }

    @Test
    fun respellingKeepsPossessiveAndRespectsSettings() {
        assertEquals("Shivawn's book.", norm("Siobhan's book.", all.copy(normalizeText = false)))
        val off = all.copy(normalizeText = false, respellNames = false)
        assertEquals("Siobhan paid $5.", norm("Siobhan paid $5.", off))
        assertEquals("Siobhan paid five dollars.", norm("Siobhan paid $5.", all.copy(respellNames = false)))
    }

    @Test
    fun defaultsMatchSmartVoices() {
        val defaults = NormalizeOptions()
        assertTrue(defaults.normalizeText)
        assertEquals(false, defaults.respellNames)
        assertEquals(DateOrder.MONTH_FIRST, defaults.dateOrder)
        assertEquals(DateOrder.DAY_FIRST, DateOrder.fromKey("day_first"))
        assertEquals(DateOrder.MONTH_FIRST, DateOrder.fromKey("bogus"))
    }

    @Test
    fun hostileInputNeverThrows() {
        for (text in listOf(
            "",
            "   ",
            "٣٤٥ ۱۲۳ १२३ 12/٣",
            "99999999999999999999999999999999999999999999999999999999",
            "#99999999999999999999999999999999999999999999 $999999999999999999999999999999999999999999.99",
            "é-Siobhan-é -Siobhan- Siobhan- -",
            "32/13 13/32 00/00 0:00:00 99:99:99 1/0 0/1 99-99-2026 2026-99-99",
            "MMMMCMXCIX Henry MMM ^^^ 2^ x^ ^2 10^999",
            "½¼¾⅓⅔⅛⁰¹²³ ⁻⁻ ¹²³⁴⁵⁶⁷⁸⁹",
            "🎉 Emoji 👋🏽 at 3:45pm 😀/😀",
        )) {
            norm(text)
        }
    }

    @Test
    fun windowSlicesStayOnCodePointBoundaries() {
        norm("${"é".repeat(50)} 3/4 ${"ü".repeat(50)}")
        norm("${"日本".repeat(30)} code ${"é".repeat(30)} A12 é")
        norm("${"😀".repeat(30)} 3/4 code ${"😀".repeat(30)} A12")
    }

    @Test
    fun unicodeClassesMatchRust() {
        // Rust \d, \w and \b are Unicode; a word with an accent is still one word.
        assertEquals("Café at five.", norm("Café at 5."))
        fun matches(rust: String, text: String) = java.util.regex.Pattern.compile(EnglishNormalizer.translate(rust))
            .matcher(text).find()
        // \b between two letters, one accented, is no boundary (ASCII \b would see one).
        assertEquals(false, matches("""é\bt""", "ét"))
        assertEquals(true, matches("""\d""", "٣"))
        assertEquals(true, matches("""^\w+$""", "naïve"))
        // $ is end of text only, and . stops only at \n.
        assertEquals(false, matches("""a$""", "a\n"))
        assertEquals(true, matches("""a.b""", "a\rb"))
        assertEquals(true, matches("""[.$]""", "$"))
    }

    @Test
    fun skippedRulesAreReported() {
        val result = EnglishNormalizer.normalizeReport("Order #4471 ships 14/03.", all)
        assertEquals(0, result.skippedRules)
    }

    /**
     * The Kotlin port must match the smart-voices Rust normalizer (and the Python it was
     * ported from) on every fixture line: the 500 English sentences of
     * `gradium/tts-hard-sentences-202610` (CC BY 4.0) plus hand-written cases per rule
     * family. Same skips and comparisons as smart-voices' `tests/normalize_golden.rs`.
     */
    @Test
    fun matchesGoldenFixture() {
        val cases = JSONArray(fixture().readText())
        assertTrue("fixture looks truncated: ${cases.length()}", cases.length() >= 500)
        val plain = NormalizeOptions(normalizeText = true, respellNames = false, dateOrder = DateOrder.DAY_FIRST)
        val names = plain.copy(respellNames = true)
        val failures = mutableListOf<String>()
        var skipped = 0
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val input = case.getString("in")
            // Intentional change from the Python, tested above: it reads 09/10/2026
            // month first and 12/10 day first in the same sentence.
            if (hasAmbiguousFullDate(input)) {
                skipped++
                continue
            }
            for ((options, key) in listOf(plain to "plain", names to "names")) {
                val got = EnglishNormalizer.normalize(input, options)
                val want = case.getString(key)
                // This app joins the lexicon's syllables (Bah-gahn is Bahgahn).
                val same = if (key == "names") comparable(got) == comparable(want) else got == want
                if (!same) failures += "[$key] $input\n  want: $want\n  got:  $got"
            }
        }
        assertTrue("skipped too many: $skipped", skipped <= 30)
        assertTrue(
            "${failures.size} of ${cases.length() * 2} differ:\n" + failures.take(40).joinToString("\n"),
            failures.isEmpty(),
        )
    }

    private fun hasAmbiguousFullDate(text: String): Boolean =
        text.split(Regex("[^0-9/]")).any { token ->
            val parts = token.split('/')
            fun small(p: String) = p.toIntOrNull()?.let { it in 1..12 } == true
            parts.size == 3 && small(parts[0]) && small(parts[1]) && parts[2].length == 4
        }

    private fun comparable(text: String): String = text.lowercase().replace("-", "")

    private fun fixture(): File {
        val candidates = listOf("src/test/resources/normalize_golden.json", "app/src/test/resources/normalize_golden.json")
        return candidates.map(::File).firstOrNull { it.isFile } ?: error("missing normalize_golden.json (tried $candidates)")
    }
}
