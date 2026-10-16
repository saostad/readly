package app.readly.text

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageDetectorTest {

    @Test
    fun dominant_persianParagraph_returnsFa() {
        assertEquals(
            "fa",
            LanguageDetector.dominant(
                "این یک متن فارسی است که باید به درستی تشخیص داده شود. " +
                    "زبان فارسی دارای حروفی مانند پ و گ و چ است که در عربی نیست.",
            ),
        )
    }

    @Test
    fun dominant_arabicParagraph_returnsAr() {
        assertEquals(
            "ar",
            LanguageDetector.dominant(
                "هذا نص عربي يجب أن يتم التعرف عليه بشكل صحيح. " +
                    "اللغة العربية لغة جميلة يتحدث بها ملايين الناس حول العالم.",
            ),
        )
    }

    @Test
    fun dominant_englishParagraph_returnsEn() {
        assertEquals(
            "en",
            LanguageDetector.dominant(
                "This is a plain English paragraph. It has no Arabic-script letters at all.",
            ),
        )
    }

    @Test
    fun dominant_englishWithOnePersianQuote_returnsEn() {
        assertEquals(
            "en",
            LanguageDetector.dominant(
                "The reporter quoted the sign at length. Witnesses in the crowd read it " +
                    "aloud, and the translation spread quickly through the evening news. " +
                    "این یک نقل قول است.",
            ),
        )
    }

    @Test
    fun dominant_emptyOrDigits_returnsEn() {
        assertEquals("en", LanguageDetector.dominant(""))
        assertEquals("en", LanguageDetector.dominant("123 … !!!"))
    }
}
