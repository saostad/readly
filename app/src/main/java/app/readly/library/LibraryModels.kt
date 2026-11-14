package app.readly.library

import app.readly.feeds.CarEntry
import app.readly.feeds.FeedTimes
import java.util.Locale

/** Unknown metadata stays unknown; it is never guessed from a headline. */
data class ArticleFacts(val language: String, val words: Int)
enum class ArticleSort(val label: String) {
    SOURCE("Feed order"), NEWEST("Newest first"), OLDEST("Oldest first"), TITLE("Title A–Z"), SITE("Website"), SHORTEST("Shortest first")
}
data class ArticleFilter(
    val unheard: Boolean = false,
    val site: String = "",
    val language: String = "",
    val maxMinutes: Int? = null,
    val wordsPerMinute: Int = DEFAULT_WORDS_PER_MINUTE,
    val sort: ArticleSort = ArticleSort.SOURCE,
) {
    companion object { const val DEFAULT_WORDS_PER_MINUTE = 180 }
    fun validate() {
        require(wordsPerMinute > 0) { "Reading pace must be greater than zero." }
        require(maxMinutes == null || maxMinutes > 0) { "Listening time must be greater than zero." }
    }
    fun apply(entries: List<CarEntry>, heard: Set<String>, facts: Map<String, ArticleFacts>): List<CarEntry> {
        validate()
        val filtered = entries.filter { entry ->
            val metadata = facts[entry.headline.url]
            (!unheard || entry.headline.url !in heard) &&
                (site.isBlank() || entry.siteLabel == site) &&
                (language.isBlank() || metadata?.language == language) &&
                (maxMinutes == null || (metadata != null && metadata.words.toLong() <= maxMinutes.toLong() * wordsPerMinute))
        }
        return when (sort) {
            ArticleSort.SOURCE -> filtered
            ArticleSort.NEWEST -> filtered.sortedWith(compareByDescending<CarEntry> { FeedTimes.parse(it.headline.published) })
            ArticleSort.OLDEST -> filtered.sortedWith(compareBy<CarEntry> { FeedTimes.parse(it.headline.published) == null }
                .thenBy { FeedTimes.parse(it.headline.published) })
            ArticleSort.TITLE -> filtered.sortedBy { it.headline.title.lowercase(Locale.ROOT) }
            ArticleSort.SITE -> filtered.sortedBy { it.siteLabel.lowercase(Locale.ROOT) }
            ArticleSort.SHORTEST -> filtered.sortedWith(compareBy<CarEntry> { facts[it.headline.url] == null }
                .thenBy { facts[it.headline.url]?.words })
        }
    }
}

data class SavedReading(val id: String, val title: String, val host: String, val language: String, val url: String, val sentences: List<String>)
data class Bookmark(val id: String, val readingId: String, val title: String, val sentence: Int, val quote: String, val note: String, val version: Int)
object BookmarkRules {
    fun validate(reading: SavedReading, sentence: Int) {
        require(reading.sentences.isNotEmpty() && reading.sentences.all { it.isNotBlank() }) { "This reading has no saved text." }
        require(sentence in reading.sentences.indices) { "This sentence is no longer available." }
    }
}

/** Literal, case-insensitive word search. Search text never becomes SQL syntax. */
object LibraryMatch {
    fun matches(query: String, vararg fields: String): Boolean {
        val haystack = fields.joinToString("\n").lowercase(Locale.ROOT)
        return query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).all { it in haystack }
    }
}
data class LibraryResult(val key: String, val title: String, val detail: String, val url: String? = null, val documentId: String? = null, val readingId: String? = null, val sentence: Int = 0)
