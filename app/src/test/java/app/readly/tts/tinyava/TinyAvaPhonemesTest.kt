package app.readly.tts.tinyava

import app.readly.tts.pocket.PocketAudio
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TinyAvaPhonemesTest {

    @Test
    fun symbolsTable_matchesExpectedInventory() {
        assertEquals(46, TinyAvaPhonemes.SYMBOLS.size)
        assertEquals("<pad>", TinyAvaPhonemes.SYMBOLS[0])
        assertEquals("<bos>", TinyAvaPhonemes.SYMBOLS[1])
        assertEquals("<eos>", TinyAvaPhonemes.SYMBOLS[2])
        assertEquals("<sp>", TinyAvaPhonemes.SYMBOLS[3])
        assertEquals("<unk>", TinyAvaPhonemes.SYMBOLS[4])
        assertEquals("،", TinyAvaPhonemes.SYMBOLS[5])
        assertEquals(".", TinyAvaPhonemes.SYMBOLS[6])
        assertEquals("؟", TinyAvaPhonemes.SYMBOLS[7])
        assertEquals("ʃ", TinyAvaPhonemes.SYMBOLS[29])
        assertEquals("ʒ", TinyAvaPhonemes.SYMBOLS[30])
        assertEquals("ɑ", TinyAvaPhonemes.SYMBOLS[45])
    }

    @Test
    fun ge2peWordToIpa_handlesEzafeZhShAndGlottal() {
        assertEquals(
            listOf(27L to 0L, 45L to 1L, 37L to 0L, 41L to 2L),
            TinyAvaPhonemes.ge2peWordToIpa("sAle1"),
        )
        assertEquals(
            listOf(
                36L to 0L, 40L to 0L, 19L to 0L, 43L to 0L,
                34L to 0L, 41L to 1L, 39L to 0L, 41L to 2L,
            ),
            TinyAvaPhonemes.ge2peWordToIpa("natijeye1"),
        )
        assertEquals(
            listOf(30L to 0L, 45L to 0L, 17L to 0L, 42L to 1L, 36L to 0L),
            TinyAvaPhonemes.ge2peWordToIpa(";Apon"),
        )
        assertEquals(
            listOf(26L to 0L, 43L to 0L, 30L to 0L, 41L to 0L, 39L to 0L, 41L to 1L),
            TinyAvaPhonemes.ge2peWordToIpa("vizheye"),
        )
        assertEquals(
            listOf(26L to 0L, 43L to 0L, 30L to 0L, 41L to 1L, 39L to 0L, 41L to 2L),
            TinyAvaPhonemes.ge2peWordToIpa("vizheye1"),
        )
        // mishavad -> prefix 'mi-' takes stress 1; final 'a' stays 0
        assertEquals(
            listOf(
                35L to 0L, 43L to 1L, 29L to 0L, 40L to 0L,
                26L to 0L, 40L to 0L, 20L to 0L,
            ),
            TinyAvaPhonemes.ge2peWordToIpa("mishavad"),
        )
        // ?ast -> unstressed copula clitic -> all 0
        assertEquals(
            listOf(40L to 0L, 27L to 0L, 19L to 0L),
            TinyAvaPhonemes.ge2peWordToIpa("?ast"),
        )
        // barAye -> barA(1) + ye(2)
        assertEquals(
            listOf(18L to 0L, 40L to 0L, 38L to 0L, 45L to 1L, 39L to 0L, 41L to 2L),
            TinyAvaPhonemes.ge2peWordToIpa("barAye"),
        )
        assertEquals(
            listOf(
                17L to 0L, 40L to 0L, 32L to 0L, 17L to 0L, 45L to 0L,
                20L to 0L, 32L to 0L, 45L to 1L, 39L to 0L, 41L to 2L,
            ),
            TinyAvaPhonemes.ge2peWordToIpa("pahpAdhAye"),
        )
    }

    @Test
    fun buildTokenSequence_insertsSpacesAndPreservesPunctuation() {
        val items = listOf(
            TinyAvaPhonemes.TokenItem.Word(TinyAvaPhonemes.ge2peWordToIpa("salAm")),
            TinyAvaPhonemes.TokenItem.Punct(5L),
            TinyAvaPhonemes.TokenItem.Word(TinyAvaPhonemes.ge2peWordToIpa("hAle1")),
            TinyAvaPhonemes.TokenItem.Word(TinyAvaPhonemes.ge2peWordToIpa("SomA")),
            TinyAvaPhonemes.TokenItem.Punct(7L),
        )
        val seq = TinyAvaPhonemes.buildTokenSequence(items)
        assertNotNull(seq)
        assertEquals(
            listOf(1L, 27L, 40L, 37L, 45L, 35L, 5L, 32L, 45L, 37L, 41L, 3L, 29L, 42L, 35L, 45L, 7L, 2L),
            seq!!.tokenIds.toList(),
        )
        assertEquals(
            listOf(0L, 0L, 0L, 0L, 1L, 0L, 0L, 0L, 1L, 0L, 2L, 0L, 0L, 0L, 0L, 1L, 0L, 0L),
            seq.stressIds.toList(),
        )
        assertNull(TinyAvaPhonemes.buildTokenSequence(listOf(TinyAvaPhonemes.TokenItem.Punct(6L))))
    }

    @Test
    fun splitLongSentence_keepsUnder350CharsWholeAndPacksOver350() {
        val voa237 =
            "این مجموعه آزمایش‌ها با عنوان شن‌های سیاه برگزار شده است و فرماندهی مرکزی ایالات متحده سنتکام، فرماندهی عملیات ویژه سنتکام، دفتر تحقیقات نیروی دریایی آمریکا، و شماری از نهادهای توسعه فناوری نظامی در آن مشارکت داشته‌اند."
        val kept = TinyAvaPhonemes.splitLongSentence(voa237, PocketAudio.PieceEnd.SENTENCE)
        assertEquals(1, kept.size)
        assertEquals(PocketAudio.PieceEnd.SENTENCE, kept[0].end)

        val over350 =
            "$voa237 در ادامه، این نهادها گزارش دادند که همکاری مشترک، همچنان با سرعت بالایی ادامه پیدا می‌کند و نتایج آن به‌زودی منتشر خواهد شد، و همچنین بخش‌های دیگر این برنامه نیز در ماه‌های آینده گسترش می‌یابند."
        assertTrue(over350.length > TinyAvaPhonemes.LONG_SENTENCE_CHARS)
        val pieces = TinyAvaPhonemes.splitLongSentence(over350, PocketAudio.PieceEnd.SENTENCE)
        assertTrue(pieces.size >= 2)
        assertTrue(pieces.all { it.text.length <= TinyAvaPhonemes.LONG_SENTENCE_CHARS })
        assertEquals(PocketAudio.PieceEnd.SENTENCE, pieces.last().end)
    }

    @Test
    fun attachWindowItems_keepsPunctuationWithAlignedWords() {
        val items = TinyAvaPhonemes.attachWindowItems(
            "سلام، دنیای زیبا.",
            listOf("salAm", "donyAye1", "zibA"),
            listOf(1, 1, 1),
        )
        assertEquals(5, items.size)
        val seq = TinyAvaPhonemes.buildTokenSequence(items)!!
        assertTrue(5L in seq.tokenIds.toList())
        assertTrue(6L in seq.tokenIds.toList())
    }

    @Test
    fun masterChunkGentle_liftsSpikySignalWithoutCrushingDynamics() {
        val spiky = FloatArray(24_000) { i ->
            val phase = (i % 160) / 160f
            if (phase < 0.08f) {
                (0.50 * sin(2.0 * PI * 300.0 * i / 24_000.0)).toFloat()
            } else {
                (0.004 * sin(2.0 * PI * 200.0 * i / 24_000.0)).toFloat()
            }
        }
        val gentle = PocketAudio.masterChunkGentle(spiky, 24_000)
        assertEquals(spiky.size, gentle.size)
        val peak = gentle.maxOf { abs(it) }
        val rms = sqrt(gentle.sumOf { it.toDouble() * it.toDouble() } / gentle.size)
        assertTrue("peak=$peak", peak <= PocketAudio.PEAK_CEILING)
        assertTrue("rms=$rms", rms in 0.08..0.22)
    }

    @Test
    fun syntacticStress_cliticsAndEzafeProtection() {
        for (clitic in listOf("va", "dar", "be", "?az", "rA", "ke", "?ast", "ham", "niz")) {
            val phones = TinyAvaPhonemes.ge2peWordToIpa(clitic)
            assertTrue("$clitic should be unstressed: $phones", phones.all { it.second == 0L })
        }
        // Ezafe homonyms stay stressed on stem and take 2 on ezafe
        assertEquals(listOf(20L to 0L, 40L to 1L, 38L to 0L, 41L to 2L), TinyAvaPhonemes.ge2peWordToIpa("dare1"))
        assertEquals(listOf(18L to 0L, 40L to 1L, 38L to 0L, 41L to 2L), TinyAvaPhonemes.ge2peWordToIpa("bare1"))
        // Demonstratives / quantifiers stay stressed
        assertEquals(listOf(43L to 1L, 36L to 0L), TinyAvaPhonemes.ge2peWordToIpa("in"))
        assertEquals(listOf(45L to 1L, 36L to 0L), TinyAvaPhonemes.ge2peWordToIpa("An"))
        assertEquals(listOf(39L to 0L, 41L to 1L, 21L to 0L), TinyAvaPhonemes.ge2peWordToIpa("yek"))
    }

    @Test
    fun syntacticStress_prefixedVerbsAndFalsePositiveGuards() {
        fun stresses(word: String): List<Long> =
            TinyAvaPhonemes.ge2peWordToIpa(word).map { it.second }

        assertEquals(listOf(0L, 1L, 0L, 0L, 0L, 0L, 0L), stresses("miSavad"))
        assertEquals(listOf(0L, 1L, 0L, 0L, 0L, 0L, 0L, 0L, 0L), stresses("nemiSavad"))
        assertEquals(listOf(0L, 1L, 0L, 0L, 0L, 0L, 0L, 0L, 0L), stresses("nakardand"))
        assertEquals(listOf(0L, 1L, 0L, 0L, 0L, 0L, 0L), stresses("bekonad"))
        assertEquals(listOf(0L, 1L, 0L, 0L, 0L, 0L), stresses("naSode"))

        // False-positive guards must retain final-vowel lexical stress
        for (adj in listOf("milAdi", "milyArdi", "milyuni", "mihani", "boland", "bozorg", "navad", "namad", "nasim", "navid", "behbudi")) {
            val phones = TinyAvaPhonemes.ge2peWordToIpa(adj)
            val vowelStresses = phones.filter { it.first in 40L..45L }.map { it.second }
            assertEquals("$adj should keep final-vowel stress: $phones", 1L, vowelStresses.last())
            assertEquals("$adj should have single stress: $phones", 1, vowelStresses.count { it == 1L })
        }
    }

    @Test
    fun syntacticStress_pastAndPerfectEndings() {
        fun vowelStresses(word: String): List<Long> =
            TinyAvaPhonemes.ge2peWordToIpa(word).filter { it.first in 40L..45L }.map { it.second }

        assertEquals(listOf(1L, 0L), vowelStresses("kardand"))
        assertEquals(listOf(0L, 1L, 0L), vowelStresses("dASte?and"))
        assertEquals(listOf(0L, 1L, 0L), vowelStresses("residand"))
        assertEquals(listOf(1L, 0L), vowelStresses("xAhad"))
    }

    @Test
    fun syntacticStress_compoundVerbDeaccentingAndGerundGuard() {
        fun wordStresses(window: String, ph: List<String>): List<List<Long>> {
            val counts = List(ph.size) { 1 }
            return TinyAvaPhonemes.attachWindowItems(window, ph, counts)
                .filterIsInstance<TinyAvaPhonemes.TokenItem.Word>()
                .map { w -> w.phones.map { it.second } }
        }

        val s1 = wordStresses("آزمایش کردند", listOf("?AzmAyeS", "kardand"))
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 1L, 0L), s1[0])
        assertTrue("kardand after content word must be unstressed: ${s1[1]}", s1[1].all { it == 0L })

        val s2 = wordStresses("صورت گرفته است", listOf("surat", "gerefte", "?ast"))
        assertEquals(listOf(0L, 0L, 0L, 1L, 0L), s2[0])
        assertTrue("gerefte deaccented: ${s2[1]}", s2[1].all { it == 0L })
        assertTrue("?ast unstressed: ${s2[2]}", s2[2].all { it == 0L })

        val s3 = wordStresses("برگزار شده است", listOf("bargozAr", "Sode", "?ast"))
        assertTrue("bargozAr stressed", s3[0].contains(1L))
        assertTrue("Sode deaccented", s3[1].all { it == 0L })
        assertTrue("?ast unstressed", s3[2].all { it == 0L })

        val s4 = wordStresses("منتشر خواهد شد", listOf("montaSer", "xAhad", "Sod"))
        assertTrue("montaSer stressed", s4[0].contains(1L))
        assertTrue("xAhad deaccented: ${s4[1]}", s4[1].all { it == 0L })
        assertTrue("Sod deaccented: ${s4[2]}", s4[2].all { it == 0L })

        val s5 = wordStresses("انجام داده شده است", listOf("anjAm", "dAde", "Sode", "?ast"))
        assertTrue("anjAm stressed", s5[0].contains(1L))
        assertTrue("dAde deaccented", s5[1].all { it == 0L })
        assertTrue("Sode deaccented", s5[2].all { it == 0L })
        assertTrue("?ast unstressed", s5[3].all { it == 0L })

        // Negative light verb keeps prefix stress
        val sNeg = wordStresses("آزمایش نکردند", listOf("?AzmAyeS", "nakardand"))
        assertEquals(listOf(0L, 1L, 0L, 0L, 0L, 0L, 0L, 0L, 0L), sNeg[1])

        // Postposition rA resets light-verb de-accenting
        val sRa = wordStresses("کتاب را گرفتند", listOf("ketAb", "rA", "gereftand"))
        assertTrue("rA unstressed", sRa[1].all { it == 0L })
        assertEquals(listOf(0L, 0L, 0L, 1L, 0L, 0L, 0L, 0L, 0L), sRa[2])

        // Punctuation resets light-verb de-accenting
        val sPunct = wordStresses("خوب، کردند", listOf("xub", "kardand"))
        assertEquals(listOf(0L, 1L, 0L, 0L, 0L, 0L, 0L), sPunct[1])

        // Compound action nouns / gerunds keep final-vowel stress
        val sGerund1 = wordStresses("تصمیم گیری", listOf("tasmim", "giri"))
        assertEquals(listOf(0L, 0L, 0L, 1L), sGerund1[1])
        val sGerund2 = wordStresses("پاک سازی", listOf("pAk", "sAzi"))
        assertEquals(listOf(0L, 0L, 0L, 1L), sGerund2[1])

        // Cross-window state stays intact across g2pWindows when punct hasn't intervened
        val state = TinyAvaPhonemes.StressState()
        val w1 = TinyAvaPhonemes.attachWindowItems("آزمایش", listOf("?AzmAyeS"), listOf(1), state)
        val w2 = TinyAvaPhonemes.attachWindowItems("کردند.", listOf("kardand"), listOf(1), state)
        assertTrue((w1[0] as TinyAvaPhonemes.TokenItem.Word).phones.any { it.second == 1L })
        assertTrue((w2[0] as TinyAvaPhonemes.TokenItem.Word).phones.all { it.second == 0L })
    }

    @Test
    fun decomposeSpeed_keepsSweetSpotAtResampleOne() {
        assertEquals(1.0f to 1.0f, TinyAvaPhonemes.decomposeSpeed(1.0f))
        assertEquals(0.75f to 1.0f, TinyAvaPhonemes.decomposeSpeed(0.75f))
        assertEquals(1.30f to 1.0f, TinyAvaPhonemes.decomposeSpeed(1.30f))
        val (nFast, rFast) = TinyAvaPhonemes.decomposeSpeed(2.0f)
        assertEquals(1.30f, nFast, 1e-6f)
        assertEquals(2.0f, nFast * rFast, 1e-4f)
        assertEquals(1.0f to 1.0f, TinyAvaPhonemes.decomposeSpeed(Float.NaN))
    }
}
