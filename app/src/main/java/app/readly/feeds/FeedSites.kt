package app.readly.feeds

import java.net.URI
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** How [HeadlineFinder] looks for a site's article titles. */
enum class FindMode(val key: String) {
    /** The site's RSS/Atom feed when it has one, otherwise the page's links. */
    AUTO("auto"),

    /** Only the page's own links (for a section page whose feed covers the whole site). */
    PAGE_LINKS("links");

    companion object {
        fun fromKey(key: String?): FindMode = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/** One website the user added for Android Auto. */
data class FeedSite(
    val id: String,
    val url: String,
    /** The user's name for it, or "" to show the host. */
    val name: String = "",
    val mode: FindMode = FindMode.AUTO,
) {
    /** Name shown in the car and the phone list. */
    fun label(): String = name.ifBlank { FeedSites.hostOf(url) }
}

/** The tunable limits for Android Auto websites (Settings → Android Auto websites). */
data class FeedLimits(val maxArticles: Int, val refreshMinutes: Int) {
    companion object {
        const val DEFAULT_MAX_ARTICLES = 25
        const val MIN_MAX_ARTICLES = 1
        const val MAX_MAX_ARTICLES = 100

        const val DEFAULT_REFRESH_MINUTES = 15
        const val MIN_REFRESH_MINUTES = 1
        const val MAX_REFRESH_MINUTES = 24 * 60

        val DEFAULT = FeedLimits(DEFAULT_MAX_ARTICLES, DEFAULT_REFRESH_MINUTES)

        /** Parses a user-typed count, or returns an error message for the field. */
        fun parseMaxArticles(input: String): Result<Int> =
            parseInRange(input, MIN_MAX_ARTICLES, MAX_MAX_ARTICLES, "Articles per website")

        fun parseRefreshMinutes(input: String): Result<Int> =
            parseInRange(input, MIN_REFRESH_MINUTES, MAX_REFRESH_MINUTES, "Refresh time")

        private fun parseInRange(input: String, min: Int, max: Int, what: String): Result<Int> {
            val value = input.trim().toIntOrNull()
            return if (value == null || value < min || value > max) {
                Result.failure(IllegalArgumentException("$what must be a whole number from $min to $max."))
            } else {
                Result.success(value)
            }
        }
    }
}

/** Pure helpers for the saved website list: address checks and the JSON codec. */
object FeedSites {

    /**
     * Turns what the user typed into a website address: trims it, adds `https://` when
     * there is no scheme, and requires http(s) with a host. Throws
     * [IllegalArgumentException] with a message for the input field.
     */
    fun normalizeUrl(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("Type a website address first.")
        if (trimmed.any { it.isWhitespace() }) {
            throw IllegalArgumentException("A website address can't contain spaces.")
        }
        val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(trimmed)) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val uri = try {
            URI(withScheme)
        } catch (_: Exception) {
            throw IllegalArgumentException("That doesn't look like a website address.")
        }
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") {
            throw IllegalArgumentException("Only http and https website addresses work.")
        }
        val host = uri.host
        if (host.isNullOrBlank() || !host.contains('.')) {
            throw IllegalArgumentException("That doesn't look like a website address.")
        }
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return "$scheme://${host.lowercase(Locale.US)}$port$path$query"
    }

    /** Adds [url] unless the same address is already saved; returns the new list. */
    fun add(sites: List<FeedSite>, url: String, name: String = ""): List<FeedSite> {
        val normalized = normalizeUrl(url)
        if (sites.any { it.url == normalized }) {
            throw IllegalArgumentException("That website is already in the list.")
        }
        return sites + FeedSite(UUID.randomUUID().toString(), normalized, name.trim())
    }

    fun hostOf(url: String): String = try {
        URI(url).host?.removePrefix("www.") ?: url
    } catch (_: Exception) {
        url
    }

    fun encode(sites: List<FeedSite>): String {
        val array = JSONArray()
        for (site in sites) {
            array.put(
                JSONObject()
                    .put("id", site.id)
                    .put("url", site.url)
                    .put("name", site.name)
                    .put("mode", site.mode.key),
            )
        }
        return array.toString()
    }

    /**
     * Decodes the saved list. Entries without an id or address are dropped and
     * reported through [onBadEntry] so a damaged list still shows what survived.
     */
    fun decode(json: String?, onBadEntry: (String) -> Unit): List<FeedSite> {
        if (json.isNullOrBlank()) return emptyList()
        val array = JSONArray(json)
        val out = mutableListOf<FeedSite>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
            val id = obj?.optString("id").orEmpty()
            val url = obj?.optString("url").orEmpty()
            if (id.isBlank() || url.isBlank()) {
                onBadEntry("Saved Android Auto website #${i + 1} has no id or address; skipped")
                continue
            }
            out += FeedSite(id, url, obj?.optString("name").orEmpty(), FindMode.fromKey(obj?.optString("mode")))
        }
        return out
    }
}
