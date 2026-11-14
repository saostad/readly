package app.readly.feeds

import java.text.ParsePosition
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Shared timestamp presentation for phone and car; feed dates always retain their offset. */
object FeedTimes {
    const val KEY_TIME_ZONE = "feedTimeZone"
    const val DEFAULT_TIME_ZONE = "America/New_York"
    const val DEVICE_TIME_ZONE = "device"

    fun zone(id: String): ZoneId = if (id == DEVICE_TIME_ZONE) ZoneId.systemDefault() else ZoneId.of(id)

    fun format(epochMs: Long, zoneId: String, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a z", locale)
            .withZone(zone(zoneId)).format(Instant.ofEpochMilli(epochMs))

    fun parse(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        val value = raw.trim()
        for (parser in listOf(DateTimeFormatter.ISO_OFFSET_DATE_TIME, DateTimeFormatter.RFC_1123_DATE_TIME)) {
            val position = ParsePosition(0)
            val instant = parser.toFormat(Instant::from).parseObject(value, position) as? Instant
            if (instant != null && position.index == value.length) return instant
        }
        return null
    }

    fun published(raw: String?, zoneId: String, onWarn: (String) -> Unit): String? {
        if (raw.isNullOrBlank()) return null
        val instant = parse(raw)
        if (instant != null) return format(instant.toEpochMilli(), zoneId)
        onWarn("fallback=true attempted=parse-publication-time reason=unsupported-date taken=unavailable-label")
        return "Publication time unavailable"
    }
}
