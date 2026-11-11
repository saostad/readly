package app.readly.feeds

import java.io.IOException
import java.text.DateFormat
import java.util.Date

/** A site's titles as served to the car or the phone preview. */
data class LoadedHeadlines(
    val list: HeadlineList,
    val fetchedAtMs: Long,
    /** True when a refresh failed and an older list is shown instead. */
    val stale: Boolean,
    /** Every note the user should see (how the list was made, why it is stale). */
    val notices: List<String>,
)

/**
 * Remembers each site's last titles for [FeedLimits.refreshMinutes], so moving back and
 * forth in the car doesn't refetch. When a refresh fails and an older list exists, that
 * list is returned marked [LoadedHeadlines.stale] with a notice saying when it is from,
 * and the failure goes to [onWarn] with `fallback=true`. With no older list the failure
 * is thrown.
 */
class HeadlineCache(
    private val finder: (FeedSite, Int) -> HeadlineList,
    private val clock: () -> Long,
    private val onWarn: (String) -> Unit,
    private val formatTime: (Long) -> String = { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) },
) {
    private data class Entry(
        val list: HeadlineList,
        val fetchedAtMs: Long,
        val url: String,
        val mode: FindMode,
        val maxArticles: Int,
    ) {
        /** False when the limit was raised past what this fetch asked for (and got). */
        fun covers(max: Int): Boolean = max <= maxArticles || list.headlines.size < maxArticles
    }

    private val entries = HashMap<String, Entry>()
    private val lock = Any()

    /** Loads [site]'s titles, from the cache when fresh unless [force]. Blocking. */
    @Throws(IOException::class)
    fun load(site: FeedSite, limits: FeedLimits, force: Boolean = false): LoadedHeadlines {
        val now = clock()
        val cached = synchronized(lock) { entries[site.id] }
            ?.takeIf { it.url == site.url && it.mode == site.mode }
        val freshForMs = limits.refreshMinutes * 60_000L
        if (!force && cached != null && now - cached.fetchedAtMs < freshForMs && cached.covers(limits.maxArticles)) {
            return LoadedHeadlines(
                cached.list.copy(headlines = cached.list.headlines.take(limits.maxArticles)),
                cached.fetchedAtMs,
                stale = false,
                notices = listOfNotNull(cached.list.notice),
            )
        }
        val list = try {
            finder(site, limits.maxArticles)
        } catch (e: IOException) {
            if (cached == null) throw e
            val time = formatTime(cached.fetchedAtMs)
            onWarn(
                "fallback=true attempted=refresh site=${site.id} host=${FeedSites.hostOf(site.url)} " +
                    "reason=\"${e.message}\" taken=cached-list-from-${cached.fetchedAtMs}",
            )
            return LoadedHeadlines(
                cached.list.copy(headlines = cached.list.headlines.take(limits.maxArticles)),
                cached.fetchedAtMs,
                stale = true,
                notices = listOfNotNull(
                    "Couldn't refresh (${e.message}), so this is the list from $time.",
                    cached.list.notice,
                ),
            )
        }
        synchronized(lock) { entries[site.id] = Entry(list, now, site.url, site.mode, limits.maxArticles) }
        return LoadedHeadlines(list, now, stale = false, notices = listOfNotNull(list.notice))
    }

    /** The cached title for [url] on any site, for the reading's title. */
    fun titleFor(url: String): String? = synchronized(lock) {
        entries.values.firstNotNullOfOrNull { entry -> entry.list.headlines.firstOrNull { it.url == url }?.title }
    }

    /** [siteId]'s last fetched titles however old, without touching the network. */
    fun peek(siteId: String): List<Headline>? = synchronized(lock) { entries[siteId]?.list?.headlines }

    fun forget(siteId: String) {
        synchronized(lock) { entries.remove(siteId) }
    }
}
