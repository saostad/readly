package app.readly.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSummariesTest {

    @Test
    fun speedProgress_roundTripsCleanly() {
        for (p in 0..15) {
            val s = SettingsSummaries.progressToSpeed(p)
            assertEquals(p, SettingsSummaries.speedToProgress(s))
        }
        assertEquals(0.5f, SettingsSummaries.progressToSpeed(0), 1e-4f)
        assertEquals(1.0f, SettingsSummaries.progressToSpeed(5), 1e-4f)
        assertEquals(2.0f, SettingsSummaries.progressToSpeed(15), 1e-4f)
        assertEquals(0, SettingsSummaries.speedToProgress(Float.NaN) - 5) // NaN -> 1.0x -> 5
    }

    @Test
    fun categorySummaries_reflectCurrentFlagsAndValues() {
        assertEquals(
            "Supertonic v2 · Tiny Ava (FA) · Smart voice On",
            SettingsSummaries.voicesSummary("Supertonic v2", "Tiny Ava", smartVoice = true),
        )
        assertEquals(
            "Kokoro · Smart voice Off",
            SettingsSummaries.voicesSummary("Kokoro", null, smartVoice = false),
        )
        assertEquals(
            "Numbers On · Day-first · Engine Auto-update",
            SettingsSummaries.readingSummary(normalize = true, dayFirst = true, autoUpdates = true),
        )
        assertEquals(
            "4 sites · Tiles · New York · Schedule Off",
            SettingsSummaries.websitesSummary(4, compact = false, timeZoneLabel = "New York", scheduleEnabled = false),
        )
        assertEquals(
            "1 site · Compact list · Tehran · Schedule On",
            SettingsSummaries.websitesSummary(1, compact = true, timeZoneLabel = "Tehran", scheduleEnabled = true),
        )
        assertEquals(
            "Play next On · Prefetch On · Pause prompts Off",
            SettingsSummaries.androidAutoSummary(playNext = true, prefetch = true, pausePrompts = false),
        )
        assertEquals(
            "On-device OCR On · Auto (EN/FA) · 2400 px",
            SettingsSummaries.documentsSummary(ocrEnabled = true, ocrLanguage = "auto", maxSide = 2400),
        )
        assertEquals(
            "Voices 112 MB (2 packs) · Clear readings & imports",
            SettingsSummaries.storageSummary(112, 2),
        )
    }

    @Test
    fun shortTimeZoneAndCharCounts_formatHumanReadableLabels() {
        assertEquals("New York", SettingsSummaries.shortTimeZoneName("America/New_York", "UTC"))
        assertEquals("Device (Los Angeles)", SettingsSummaries.shortTimeZoneName("device", "America/Los_Angeles"))
        assertEquals("Tehran", SettingsSummaries.shortTimeZoneName("Asia/Tehran", "UTC"))
        assertEquals("2M chars", SettingsSummaries.formatCharCount(2_000_000))
        assertEquals("500K chars", SettingsSummaries.formatCharCount(500_000))
        assertEquals("1.5M chars", SettingsSummaries.formatCharCount(1_500_000))
    }
}
