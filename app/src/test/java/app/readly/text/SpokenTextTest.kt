package app.readly.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SpokenTextTest {

    @Test
    fun englishIsNormalizedOtherLanguagesAreNot() {
        val options = NormalizeOptions()
        assertEquals("It weighs two kilograms.", SpokenText.forVoice("It weighs 2 kg.", "en", options).text)
        assertEquals("سلام 5 kg.", SpokenText.forVoice("سلام 5 kg.", "fa", options).text)
        assertEquals("Pesa 2 kg.", SpokenText.forVoice("Pesa 2 kg.", "es", options).text)
        assertFalse(SpokenText.forVoice("It weighs 2 kg.", "en", options).degraded)
    }

    @Test
    fun switchedOffReadsAsWritten() {
        val off = NormalizeOptions(normalizeText = false, respellNames = false)
        assertEquals("It weighs 2 kg.", SpokenText.forVoice("It weighs 2 kg.", "en", off).text)
    }
}
