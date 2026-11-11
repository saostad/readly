package app.readly.feeds

/**
 * The list an Android Auto reading came from (a website, Latest, Saved, Recents or a
 * shuffle of one), so the car's ⏮ / ⏭ and Up next move through it. Immutable: moving
 * returns a new queue.
 */
data class CarQueue(val title: String, val entries: List<CarEntry>, val index: Int) {

    val current: CarEntry get() = entries[index]
    val hasNext: Boolean get() = index < entries.lastIndex
    val hasPrevious: Boolean get() = index > 0

    /** The queue moved by [delta] articles, or null past either end. */
    fun moveBy(delta: Int): CarQueue? = jumpTo(index + delta)

    /** The queue at article [target], or null outside the list. */
    fun jumpTo(target: Int): CarQueue? = if (target in entries.indices) copy(index = target) else null

    companion object {
        /** A queue at [url] in [entries], or null when the article isn't in the list. */
        fun at(title: String, entries: List<CarEntry>, url: String): CarQueue? {
            val index = entries.indexOfFirst { it.headline.url == url }
            return if (index < 0) null else CarQueue(title, entries, index)
        }

        /** [entries] in a random order from [random], starting at the first. */
        fun shuffled(title: String, entries: List<CarEntry>, random: kotlin.random.Random): CarQueue? =
            if (entries.isEmpty()) null else CarQueue(title, entries.shuffled(random), 0)
    }
}
