package app.readly.text

import org.junit.Assert.assertEquals
import org.junit.Test

/** Port of the `fa_text.rs` unit tests in smart-voices. */
class FaTextTest {

    @Test
    fun datesAndTimes() {
        assertEquals("ساعت 8 و 30 دقیقه صبح", FaText.expand("ساعت ۸:۳۰ صبح"))
        assertEquals("10", FaText.expand("۱۰:۰۰"))
        assertEquals("8 و 5 دقیقه و 9 ثانیه", FaText.expand("08:05:09"))
        assertEquals("14 مهر 1405", FaText.expand("۱۴۰۵/۰۷/۱۴"))
        assertEquals("13 نوامبر 2026", FaText.expand("2026-11-13"))
        assertEquals("۱۴۰۵/۱۳/۰۱", FaText.expand("۱۴۰۵/۱۳/۰۱"))
    }

    @Test
    fun currencySymbolsAndAbbreviations() {
        assertEquals("25 دلار", FaText.expand("$۲۵"))
        assertEquals("20 یورو", FaText.expand("۲۰€"))
        assertEquals("5 میلیون دلار", FaText.expand("$۵ میلیون"))
        assertEquals("25 درجه سانتی\u200Cگراد", FaText.expand("۲۵°C"))
        assertEquals("2 به\u200Cعلاوه 2 مساوی 4", FaText.expand("۲+۲=۴"))
        assertEquals("a ات b دات com", FaText.expand("a@b.com"))
        assertEquals("3.5", FaText.expand("3.5"))
        assertEquals("سال ۱۳۵۷ هجری شمسی", FaText.expand("سال ۱۳۵۷ ه.ش"))
        assertEquals("۵۰۰ قبل از میلاد", FaText.expand("۵۰۰ ق.م."))
        assertEquals("شماره 3", FaText.expand("#۳"))
    }

    @Test
    fun rangesSignsAndDecimals() {
        assertEquals("0912-345-6789", FaText.expand("0912-345-6789"))
        assertEquals("978-3-16", FaText.expand("978-3-16"))
        assertEquals("1404 تا 1405", FaText.expand("۱۴۰۴–۱۴۰۵"))
        assertEquals("5 تا 10 نفر", FaText.expand("۵-۱۰ نفر"))
        assertEquals("021-88776655", FaText.expand("021-88776655"))
        assertEquals("دما منفی 5 درجه", FaText.expand("دما -۵ درجه"))
        assertEquals("3.5 درصد", FaText.expand("۳/۵ درصد"))
        assertEquals("متن ساده بدون عدد.", FaText.expand("متن ساده بدون عدد."))
    }
}
