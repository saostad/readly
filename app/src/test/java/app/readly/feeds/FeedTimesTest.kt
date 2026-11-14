package app.readly.feeds

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

class FeedTimesTest {
    @Test fun rssAndAtomDatesConvertToNewYorkAcrossDayBoundary() {
        val oldLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val expected = "Nov 13, 2026, 10:06 PM EST"
            for (raw in listOf("Sat, 14 Nov 2026 03:06:59 +0000", "2026-11-14T03:06:59Z", "2026-11-14T04:06:59+01:00")) {
                assertEquals(expected, FeedTimes.published(raw, FeedTimes.DEFAULT_TIME_ZONE) { fail(it) })
            }
        } finally {
            Locale.setDefault(oldLocale)
        }
    }

    @Test fun newYorkAdjustsForDaylightSavingAndSupportsOtherZones() {
        val summer = Instant.parse("2026-07-14T03:06:59Z").toEpochMilli()
        assertEquals("Jul 13, 2026, 11:06 PM EDT", FeedTimes.format(summer, FeedTimes.DEFAULT_TIME_ZONE, Locale.US))
        assertEquals("Jul 14, 2026, 3:06 AM UTC", FeedTimes.format(summer, "UTC", Locale.US))
    }

    @Test fun deviceZoneFollowsDeviceAndDoesNotChangeDefault() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            val at = Instant.parse("2026-11-14T03:06:59Z").toEpochMilli()
            assertEquals("Nov 14, 2026, 12:06 PM JST", FeedTimes.format(at, FeedTimes.DEVICE_TIME_ZONE, Locale.US))
            assertEquals("Nov 13, 2026, 10:06 PM EST", FeedTimes.format(at, FeedTimes.DEFAULT_TIME_ZONE, Locale.US))
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test fun invalidDateHasVisibleNoticeAndWarningInsteadOfRawUtc() {
        val warnings = mutableListOf<String>()
        assertEquals("Publication time unavailable", FeedTimes.published("invalid", "UTC", warnings::add))
        assertTrue(warnings.single().contains("fallback=true"))
        assertNull(FeedTimes.published(null, "UTC") { fail(it) })
        assertNull(FeedTimes.published(" ", "UTC") { fail(it) })
    }
}
