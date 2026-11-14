package app.readly.library

import android.content.Context
import app.readly.feeds.FeedRepository
import app.readly.input.pdf.PdfImportStore

/** One search service for all clients. Reads local data only and fails visibly as a whole. */
class LibraryService(private val context: Context, private val feeds: FeedRepository) {
    fun search(query: String): List<LibraryResult> {
        val results = linkedMapOf<String, LibraryResult>()
        val hidden = feeds.hidden()
        fun article(url: String, title: String, site: String, kind: String) {
            if (url !in hidden && LibraryMatch.matches(query, title, site, url)) {
                results.putIfAbsent(url, LibraryResult(url, title, "$kind · $site", url = url))
            }
        }
        for (site in feeds.sites()) feeds.cachedHeadlines(site.id).orEmpty().forEach { article(it.url, it.title, site.label(), "Article") }
        feeds.saved().forEach { article(it.url, it.title, "Saved", "Read later") }
        feeds.history().forEach { article(it.url, it.title, it.siteLabel, "Listening history") }
        feeds.historyWarning?.let { throw IllegalStateException(it) }
        PdfImportStore(context).use { store -> store.search(query).forEach { results[it.key] = it } }
        ReadingStore(context).use { store ->
            store.searchReadings(query).forEach { results[it.key] = it }
            store.bookmarks().filter { LibraryMatch.matches(query, it.title, it.quote, it.note) }.forEach {
                results[it.id] = LibraryResult(it.id, it.title, "Bookmark · ${it.quote}\n${it.note}", readingId = it.readingId, sentence = it.sentence)
            }
        }
        return results.values.toList()
    }
}
