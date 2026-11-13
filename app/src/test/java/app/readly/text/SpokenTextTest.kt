package app.readly.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenTextTest {

    @Test
    fun englishIsNormalizedOtherLanguagesAreNot() {
        val options = NormalizeOptions()
        assertEquals("It weighs two kilograms.", SpokenText.forVoice("It weighs 2 kg.", "en", options).text)
        assertEquals("سلام 5 کیلوگرم.", SpokenText.forVoice("سلام 5 kg.", "fa", options).text)
        assertEquals("Pesa 2 kg.", SpokenText.forVoice("Pesa 2 kg.", "es", options).text)
        assertFalse(SpokenText.forVoice("It weighs 2 kg.", "en", options).degraded)
    }

    @Test
    fun switchedOffReadsAsWritten() {
        val off = NormalizeOptions(normalizeText = false, respellNames = false)
        assertEquals("It weighs 2 kg.", SpokenText.forVoice("It weighs 2 kg.", "en", off).text)
    }

    @Test
    fun persianTimeIsExpandedAndNotDegraded() {
        val result = SpokenText.forVoice("جلسه ساعت ۸:۳۰ است.", "fa", NormalizeOptions())
        assertEquals("جلسه ساعت 8 و 30 دقیقه است.", result.text)
        assertFalse(result.degraded)
        assertNull(result.notice)
    }

    @Test
    fun persianListedLatinWordIsReadAndNotDegraded() {
        val result = SpokenText.forVoice("فایل PDF را بفرست", "fa", NormalizeOptions())
        assertEquals("فایل پی\u200Cدی\u200Cاف را بفرست", result.text)
        assertFalse(result.degraded)
        assertNull(result.notice)
    }

    @Test
    fun persianUnlistedWordIsDegradedApproximateAndLoggedByCountOnly() {
        val warnings = mutableListOf<String>()
        val oldSink = NormalizerLog.warnSink
        NormalizerLog.warnSink = { warnings += it }
        try {
            val result = SpokenText.forVoice("سلام cluster", "fa", NormalizeOptions())
            assertTrue(result.degraded)
            assertEquals(SpokenNotice.APPROXIMATE_PERSIAN, result.notice)
        } finally {
            NormalizerLog.warnSink = oldSink
        }
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("attempted=english_word_list"))
        assertTrue(warnings[0].contains("count=1"))
        assertFalse("log must not contain user text", warnings[0].contains("cluster"))
    }

    @Test
    fun persianUnsupportedScriptIsDegradedSkipped() {
        val result = SpokenText.forVoice("سلام Привет", "fa", NormalizeOptions())
        assertTrue(result.degraded)
        assertEquals(SpokenNotice.SKIPPED_TEXT, result.notice)
    }
}
