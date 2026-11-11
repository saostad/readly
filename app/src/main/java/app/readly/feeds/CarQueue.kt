package app.readly.feeds

/**
 * The website list an Android Auto reading came from, so the car's ⏮ / ⏭ move to
 * the previous or next article in it. Immutable: moving returns a new queue.
 */
data class CarQueue(val site: FeedSite, val headlines: List<Headline>, val index: Int) {

    val current: Headline get() = headlines[index]
    val hasNext: Boolean get() = index < headlines.lastIndex
    val hasPrevious: Boolean get() = index > 0

    /** The queue moved by [delta] articles, or null past either end. */
    fun moveBy(delta: Int): CarQueue? {
        val target = index + delta
        return if (target in headlines.indices) copy(index = target) else null
    }

    companion object {
        /** A queue at [url] in [headlines], or null when the article isn't in the list. */
        fun at(site: FeedSite, headlines: List<Headline>, url: String): CarQueue? {
            val index = headlines.indexOfFirst { it.url == url }
            return if (index < 0) null else CarQueue(site, headlines, index)
        }
    }
}
