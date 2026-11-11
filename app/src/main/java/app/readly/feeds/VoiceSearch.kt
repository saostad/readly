package app.readly.feeds

import java.util.Locale

/**
 * Picks the article to read for a voice request ("play the BBC on Readly", "play
 * the budget story on Readly"): an empty request reads the newest article on the first
 * website, a request naming a website reads its newest article, and anything else reads
 * the title sharing the most words with the request.
 */
object VoiceSearch {

    sealed class Pick {
        data class Article(val headline: Headline, val site: FeedSite) : Pick()
        data class Failure(val message: String) : Pick()
    }

    /** Words this short ("the", "on", "a") say nothing about which article. */
    private const val MIN_WORD_CHARS = 3

    /** A shorter request (e.g. "bbc") must match a site name exactly, not inside it. */
    private const val MIN_COMPACT_CHARS = 4

    /** Words that are part of asking, not of the article. */
    private val FILLER = setOf(
        "the", "and", "for", "from", "with", "about", "play", "read", "readly", "article", "story", "please",
    )

    /**
     * [load] returns a site's titles, or throws; failed sites are skipped and named in
     * the failure message when nothing else matched.
     */
    fun pick(query: String?, sites: List<FeedSite>, load: (FeedSite) -> List<Headline>): Pick {
        if (sites.isEmpty()) {
            return Pick.Failure("No websites yet. Add them in Readly on your phone.")
        }
        val failed = mutableListOf<String>()
        val loaded = LinkedHashMap<FeedSite, List<Headline>>()
        fun headlines(site: FeedSite): List<Headline> = loaded.getOrPut(site) {
            try {
                load(site)
            } catch (e: Exception) {
                failed += "${site.label()} (${e.message ?: e.javaClass.simpleName})"
                emptyList()
            }
        }
        fun failure(prefix: String): Pick.Failure = Pick.Failure(
            if (failed.isEmpty()) prefix else "$prefix Couldn't load ${failed.joinToString(", ")}.",
        )

        val q = query?.trim().orEmpty().lowercase(Locale.ROOT)
        if (q.isEmpty()) {
            for (site in sites) headlines(site).firstOrNull()?.let { return Pick.Article(it, site) }
            return failure("Your websites have no articles right now.")
        }
        // "the verge" names theverge.com: compare without spaces or punctuation.
        val compactQuery = compact(q)
        val named = sites.firstOrNull { site ->
            val label = compact(site.label())
            val hostStem = FeedSites.hostOf(site.url).lowercase(Locale.ROOT).substringBefore('.')
            label in compactQuery ||
                (compactQuery.length >= MIN_COMPACT_CHARS && compactQuery in label) ||
                hostStem in words(q)
        }
        if (named != null) {
            headlines(named).firstOrNull()?.let { return Pick.Article(it, named) }
            return failure("${named.label()} has no articles right now.")
        }
        val wanted = words(q)
        var best: Pick.Article? = null
        var bestScore = 0
        for (site in sites) {
            for (headline in headlines(site)) {
                val score = words(headline.title.lowercase(Locale.ROOT)).count { it in wanted }
                if (score > bestScore) {
                    best = Pick.Article(headline, site)
                    bestScore = score
                }
            }
        }
        return best ?: failure("Couldn't find \"${query?.trim()}\" in your Readly websites.")
    }

    private fun words(text: String): Set<String> =
        text.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= MIN_WORD_CHARS && it !in FILLER }
            .toSet()

    private fun compact(text: String): String =
        text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), "")
}
