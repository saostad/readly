package app.readly.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of the `latin_fa.rs` unit tests in smart-voices. */
class LatinFaTest {

    @Test
    fun spellingRulesOnly() {
        val cases = listOf(
            "chat" to "چت",
            "smart" to "اسمارت",
            "start" to "استارت",
            "web" to "وب",
            "test" to "تست",
            "link" to "لینک",
            "google" to "گوگل",
            "server" to "سرور",
            "bug" to "باگ",
            "linux" to "لیناکس",
            "java" to "جاوا",
            "name" to "نیم",
            "phone" to "فون",
            "error" to "ارور",
            "update" to "اپدیت",
            "cluster" to "کلاستر",
            "page" to "پیج",
            "book" to "بوک",
            "team" to "تیم",
            "cloud" to "کلود",
            "city" to "سیتی",
            "each" to "ایچ",
        )
        for ((input, expected) in cases) {
            assertEquals("rule spelling of $input", expected, LatinFa.spellByRules(input))
        }
    }

    @Test
    fun persianizeWholeText() {
        assertEquals("فایل پی\u200Cدی\u200Cاف را", LatinFa.persianize("فایل PDF را").text)
        assertEquals("چت\u200Cجی\u200Cپی\u200Cتی", LatinFa.persianize("ChatGPT").text)
        assertEquals("وی\u200Cاس کد", LatinFa.persianize("VS Code").text)
        assertEquals("آیفون", LatinFa.persianize("iPhone").text)
        assertEquals("ای\u200Cپی\u200Cآی\u200Cها", LatinFa.persianize("APIها").text)
    }

    @Test
    fun persianizeLeavesPersianAndReports() {
        val plain = LatinFa.persianize("سلام دنیا، این یک متن فارسی است.")
        assertEquals("سلام دنیا، این یک متن فارسی است.", plain.text)
        assertTrue(plain.approximated.isEmpty())
        assertFalse(plain.skipped)

        val skipped = LatinFa.persianize("سلام Привет")
        assertTrue(skipped.skipped)

        // A word-list word is not approximated; a rules word is, once.
        val mixed = LatinFa.persianize("code cluster cluster")
        assertEquals("کد کلاستر کلاستر", mixed.text)
        assertEquals(listOf("cluster"), mixed.approximated)
    }

    @Test
    fun phonemeOverridesMapPersianToPhonemes() {
        val overrides = LatinFa.phonemeOverrides()
        assertEquals("?eksel", overrides["اکسل"])
        assertEquals("gugel", overrides["گوگل"])
        assertEquals("fermver", overrides["فرم\u200Cور"])
        // Spellings that are ordinary Persian words have an empty
        // phonemes column, so they get no override.
        for (persian in listOf(
            "نود", "شیر", "منو", "دومین", "گو", "نت", "ارگ", "ورد", "سرور",
            "پست", "رم", "کابل", "گیت", "پرو", "پلی", "کش", "راست",
        )) {
            assertFalse("$persian must have no phoneme override", overrides.containsKey(persian))
        }
    }

    @Test
    fun wordListTableIsWellFormed() {
        val stream = javaClass.getResourceAsStream("/app/readly/text/latin_fa.tsv")
            ?: throw IllegalStateException("test resource /app/readly/text/latin_fa.tsv is missing")
        val lines = stream.bufferedReader(Charsets.UTF_8).readLines()
        val keys = mutableListOf<String>()
        val alphabet = "aeoAiubptsjChxdzrSqfkglmnvy?;"
        fun isPhonemeConsonant(c: Char): Boolean = c !in "aeoAiu"
        for ((index, line) in lines.withIndex()) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val columns = line.split('\t')
            assertEquals("line ${index + 1} must have three columns: $line", 3, columns.size)
            val written = columns[0]
            val persian = columns[1]
            val phonemes = columns[2]
            assertTrue(
                "line ${index + 1} key is not lower-case ASCII letters: $written",
                written.isNotEmpty() && written.all { it in 'a'..'z' },
            )
            assertTrue(
                "line ${index + 1} value has a char outside Persian letters, ZWNJ and spaces: $persian",
                persian.isNotEmpty() &&
                    persian.all { LatinFa.isArabicScript(it) || it == ' ' || it == '\u200C' },
            )
            // The phonemes column may be empty (no override); when it
            // is present it must use only the v2 alphabet.
            assertTrue(
                "line ${index + 1} phonemes use a char outside the v2 alphabet: $phonemes",
                phonemes.all { it != ' ' && it in alphabet },
            )
            // No syllable starts with two consonants (except `pro`/`br`,
            // which the model knows) and no four consonants in a row.
            val chars = phonemes.toList()
            assertTrue(
                "line ${index + 1} phonemes start with two consonants: $phonemes",
                chars.size < 2 || !isPhonemeConsonant(chars[0]) || !isPhonemeConsonant(chars[1]) ||
                    phonemes.startsWith("pro") || phonemes.startsWith("br"),
            )
            assertTrue(
                "line ${index + 1} phonemes have four consonants in a row: $phonemes",
                chars.windowed(4).none { window -> window.all { isPhonemeConsonant(it) } },
            )
            keys += written
        }
        // Two rows with the same Persian spelling have the same phonemes.
        val byPersian = HashMap<String, String>()
        for (line in lines) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val columns = line.split('\t')
            val previous = byPersian.put(columns[1], columns[2])
            if (previous != null) assertEquals("duplicate Persian spelling", previous, columns[2])
        }
        assertEquals("word list keys are not sorted", keys.sorted(), keys)
        assertEquals("word list has duplicate keys", keys.distinct().size, keys.size)
        assertTrue("word list should have about 300 entries, has ${keys.size}", keys.size in 250..400)
    }
}
