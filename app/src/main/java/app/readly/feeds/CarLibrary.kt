package app.readly.feeds

import org.json.JSONArray
import org.json.JSONObject

/** One row the car listed: an article and where it came from. [listId] names the list. */
data class CarEntry(
    val listId: String,
    val headline: Headline,
    val siteLabel: String,
)

/** A link shared to "Read later", shown in the car's Saved tab. */
data class SavedArticle(val url: String, val title: String, val addedAtMs: Long)

/**
 * How far an Android Auto article got, so it resumes there and the car can mark it.
 * Offsets count characters of the article body (never the spoken intro).
 */
data class HistoryEntry(
    val url: String,
    val title: String,
    val siteLabel: String,
    val offsetChars: Int,
    val bodyChars: Int,
    val finished: Boolean,
    val updatedAtMs: Long,
) {
    /** 0..1 of the article heard (1 when finished). */
    val fraction: Double
        get() = when {
            finished -> 1.0
            bodyChars <= 0 -> 0.0
            else -> (offsetChars.toDouble() / bodyChars).coerceIn(0.0, 1.0)
        }

    /** Worth resuming: started, not finished. */
    val resumable: Boolean get() = !finished && offsetChars > 0
}

/** The Saved list: newest first, one row per address. Pure; stored as JSON. */
object ReadLater {

    fun add(list: List<SavedArticle>, url: String, title: String, nowMs: Long): List<SavedArticle> {
        val clean = url.trim()
        require(clean.startsWith("http://") || clean.startsWith("https://")) { "That isn't a web link." }
        val label = title.trim().ifBlank { FeedSites.hostOf(clean) }
        return listOf(SavedArticle(clean, label, nowMs)) + list.filterNot { it.url == clean }
    }

    fun remove(list: List<SavedArticle>, url: String): List<SavedArticle> = list.filterNot { it.url == url }

    /** Puts a removed row back where it was (Undo), ordered by when it was saved. */
    fun restore(list: List<SavedArticle>, article: SavedArticle): List<SavedArticle> =
        (list.filterNot { it.url == article.url } + article).sortedByDescending { it.addedAtMs }

    /** Renames a saved row once the article's real title is known. */
    fun retitle(list: List<SavedArticle>, url: String, title: String): List<SavedArticle> =
        if (title.isBlank()) list else list.map { if (it.url == url) it.copy(title = title) else it }

    fun encode(list: List<SavedArticle>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("url", it.url).put("title", it.title).put("added", it.addedAtMs)) }
    }.toString()

    /** @throws org.json.JSONException when the stored list is corrupt. */
    fun decode(json: String?): List<SavedArticle> {
        if (json.isNullOrBlank()) return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            SavedArticle(o.getString("url"), o.optString("title"), o.optLong("added"))
        }
    }
}

/** Listening history: newest first, one entry per address, at most [MAX_ENTRIES]. */
object ListeningHistory {

    /** Storage cap so the history stays a small preference value. */
    const val MAX_ENTRIES = 200

    fun record(
        list: List<HistoryEntry>,
        url: String,
        title: String,
        siteLabel: String,
        offsetChars: Int,
        bodyChars: Int,
        nowMs: Long,
    ): List<HistoryEntry> {
        val previous = list.firstOrNull { it.url == url }
        val entry = HistoryEntry(
            url = url,
            title = title.ifBlank { previous?.title.orEmpty() },
            siteLabel = siteLabel.ifBlank { previous?.siteLabel.orEmpty() },
            offsetChars = offsetChars.coerceIn(0, bodyChars.coerceAtLeast(0)),
            bodyChars = bodyChars,
            // Listening again from the start doesn't un-hear an article.
            finished = previous?.finished == true && offsetChars == 0,
            updatedAtMs = nowMs,
        )
        return (listOf(entry) + list.filterNot { it.url == url }).take(MAX_ENTRIES)
    }

    /**
     * Marks [url] heard from the article menu, even when it was never played (no
     * entry yet: one is added with no length, which counts as fully heard).
     */
    fun markHeard(list: List<HistoryEntry>, url: String, title: String, siteLabel: String, nowMs: Long): List<HistoryEntry> {
        val previous = list.firstOrNull { it.url == url }
        val entry = previous?.copy(finished = true, offsetChars = previous.bodyChars, updatedAtMs = nowMs)
            ?: HistoryEntry(url, title, siteLabel, 0, 0, finished = true, updatedAtMs = nowMs)
        return (listOf(entry) + list.filterNot { it.url == url }).take(MAX_ENTRIES)
    }

    /** Forgets [url]: it shows as unheard again and loses its resume point. */
    fun forget(list: List<HistoryEntry>, url: String): List<HistoryEntry> = list.filterNot { it.url == url }

    /** Puts [entry] back as it was (Undo), ordered by when it was last updated. */
    fun restore(list: List<HistoryEntry>, entry: HistoryEntry): List<HistoryEntry> =
        (list.filterNot { it.url == entry.url } + entry).sortedByDescending { it.updatedAtMs }.take(MAX_ENTRIES)

    fun finish(list: List<HistoryEntry>, url: String, nowMs: Long): List<HistoryEntry> {
        val entry = list.firstOrNull { it.url == url } ?: return list
        val done = entry.copy(finished = true, offsetChars = entry.bodyChars, updatedAtMs = nowMs)
        return listOf(done) + list.filterNot { it.url == url }
    }

    fun encode(list: List<HistoryEntry>): String = JSONArray().apply {
        list.forEach {
            put(
                JSONObject().put("url", it.url).put("title", it.title).put("site", it.siteLabel)
                    .put("offset", it.offsetChars).put("body", it.bodyChars)
                    .put("done", it.finished).put("at", it.updatedAtMs),
            )
        }
    }.toString()

    /** @throws org.json.JSONException when the stored history is corrupt. */
    fun decode(json: String?): List<HistoryEntry> {
        if (json.isNullOrBlank()) return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            HistoryEntry(
                o.getString("url"), o.optString("title"), o.optString("site"),
                o.optInt("offset"), o.optInt("body"), o.optBoolean("done"), o.optLong("at"),
            )
        }
    }
}

/**
 * Articles hidden from the website lists and Latest (the article menu's "Hide"):
 * newest first, one entry per address, at most [MAX_ENTRIES]. Stored as JSON.
 */
object HiddenArticles {

    /** Storage cap: past it the oldest hidden address shows again. */
    const val MAX_ENTRIES = 500

    fun hide(list: List<String>, url: String): List<String> {
        val clean = url.trim()
        require(clean.isNotEmpty()) { "That article has no address." }
        return (listOf(clean) + list.filterNot { it == clean }).take(MAX_ENTRIES)
    }

    fun unhide(list: List<String>, url: String): List<String> = list.filterNot { it == url.trim() }

    fun encode(list: List<String>): String = JSONArray().apply { list.forEach { put(it) } }.toString()

    /** @throws org.json.JSONException when the stored list is corrupt. */
    fun decode(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { array.getString(it) }
    }
}

/**
 * The Latest tab: each site's newest articles taking turns (first of every site, then
 * the second of every site, …), so one busy site can't crowd out the rest. Duplicate
 * addresses keep their first place.
 */
object LatestMix {
    fun interleave(lists: List<Pair<String, List<Headline>>>, max: Int): List<CarEntry> {
        val out = mutableListOf<CarEntry>()
        val seen = mutableSetOf<String>()
        val longest = lists.maxOfOrNull { it.second.size } ?: 0
        for (row in 0 until longest) {
            for ((label, headlines) in lists) {
                val headline = headlines.getOrNull(row) ?: continue
                if (!seen.add(headline.url)) continue
                out += CarEntry(LATEST_LIST, headline, label)
                if (out.size >= max) return out
            }
        }
        return out
    }

    const val LATEST_LIST = "latest"
}
