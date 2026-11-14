package app.readly.feeds

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import app.readly.library.*
import app.readly.input.ArticleFetcher
import app.readly.playback.ReaderService
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The one place the phone screen and the Android Auto browser read and change the
 * saved websites, their limits and their cached titles. Lives on [app.readly.ReadlyApp]
 * so the phone preview warms the car's cache.
 */
class FeedRepository(context: Context) {

    companion object {
        const val KEY_SITES = "autoSites"
        const val KEY_MAX_ARTICLES = "autoMaxArticles"
        const val KEY_REFRESH_MINUTES = "autoRefreshMinutes"

        /** After an Android Auto article ends, start the next one in its site's list (default off). */
        const val KEY_PLAY_NEXT = "autoPlayNext"

        /** Links shared to "Read later" (the car's Saved tab), JSON. */
        const val KEY_SAVED = "autoSaved"

        /** Where each Android Auto article stopped (Recents, resume, heard marks), JSON. */
        const val KEY_HISTORY = "autoHistory"

        /** Articles hidden from the website lists and Latest, JSON. */
        const val KEY_HIDDEN = "autoHidden"

        /** Open the next article in the list while one plays (default on). */
        const val KEY_PREFETCH = "autoPrefetch"

        /** Say the site, title and length before each car article (default on). */
        const val KEY_INTRO = "autoIntro"

        /** Start the next unheard article when Android Auto opens Readly (default off). */
        const val KEY_AUTO_PLAY = "autoPlayOnConnect"
        private const val KEY_UPDATED_PREFIX = "feedUpdated:"
        private const val TAG = "Readly"
    }

    private val appContext = context.applicationContext

    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(ReaderService.PREFS_NAME, Context.MODE_PRIVATE)

    private val cache = HeadlineCache(
        finder = { site, max ->
            HeadlineFinder(ArticleFetcher::httpUrlOpener) { Log.w(TAG, "Android Auto headlines: $it") }
                .find(site.url, site.mode, max)
        },
        clock = System::currentTimeMillis,
        onWarn = { Log.w(TAG, "Android Auto headlines: $it") },
        formatTime = { updatedTime(it) },
    )

    /**
     * The saved websites. A list that can't be read at all is reported and thrown, so
     * neither screen shows an empty list as if nothing was saved.
     */
    @Throws(IOException::class)
    fun sites(): List<FeedSite> = try {
        FeedSites.decode(prefs.getString(KEY_SITES, null)) { Log.e(TAG, it) }
    } catch (e: org.json.JSONException) {
        Log.e(TAG, "Saved Android Auto websites are unreadable: ${e.message}", e)
        throw IOException("Your saved Android Auto websites couldn't be read. Remove and add them again.", e)
    }

    fun site(id: String): FeedSite? = try {
        sites().firstOrNull { it.id == id }
    } catch (_: IOException) {
        null
    }

    /** Validates and saves [url]. Throws [IllegalArgumentException] with a message for the field. */
    fun add(url: String, name: String = ""): FeedSite {
        val updated = FeedSites.add(sites(), url, name)
        save(updated)
        return updated.last()
    }

    fun remove(id: String) {
        save(sites().filterNot { it.id == id })
        ReadingStore(appContext).use { it.removeFeed(id) }
        cache.forget(id)
        prefs.edit().remove(KEY_UPDATED_PREFIX + id).apply()
    }

    fun setMode(id: String, mode: FindMode) {
        save(sites().map { if (it.id == id) it.copy(mode = mode) else it })
        ReadingStore(appContext).use { it.removeFeed(id) }
        cache.forget(id)
        prefs.edit().remove(KEY_UPDATED_PREFIX + id).apply()
    }

    private fun save(sites: List<FeedSite>) {
        prefs.edit().putString(KEY_SITES, FeedSites.encode(sites)).apply()
    }

    fun filter(): ArticleFilter = ArticleFilter(
        prefs.getBoolean("filterUnheard", false), prefs.getString("filterSite", "").orEmpty(),
        prefs.getString("filterLanguage", "").orEmpty(), prefs.getInt("filterMinutes", 0).takeIf { it > 0 },
        prefs.getInt("filterPace", ArticleFilter.DEFAULT_WORDS_PER_MINUTE),
        ArticleSort.valueOf(prefs.getString("filterSort", ArticleSort.SOURCE.name)!!),
    ).also { it.validate() }

    fun saveFilter(filter: ArticleFilter) {
        filter.validate()
        prefs.edit().putBoolean("filterUnheard", filter.unheard).putString("filterSite", filter.site)
            .putString("filterLanguage", filter.language).putInt("filterMinutes", filter.maxMinutes ?: 0)
            .putInt("filterPace", filter.wordsPerMinute).putString("filterSort", filter.sort.name).apply()
        itemsChanged()
    }

    fun filterArticles(entries: List<CarEntry>): List<CarEntry> = ReadingStore(appContext).use { store ->
        filter().apply(entries, history().filter { it.finished }.map { it.url }.toSet(), store.facts())
    }

    fun articleFacts(): Map<String, ArticleFacts> = ReadingStore(appContext).use { it.facts() }

    fun timeZoneId(): String = prefs.getString(FeedTimes.KEY_TIME_ZONE, FeedTimes.DEFAULT_TIME_ZONE)!!

    fun updatedTime(epochMs: Long): String = FeedTimes.format(epochMs, timeZoneId())

    fun publicationTime(raw: String?): String? = FeedTimes.published(raw, timeZoneId()) { Log.w(TAG, it) }

    fun lastUpdated(siteId: String): Long? = cache.fetchedAt(siteId)
        ?: prefs.getLong(KEY_UPDATED_PREFIX + siteId, 0L).takeIf { it > 0L }

    fun limits(): FeedLimits = FeedLimits(
        prefs.getInt(KEY_MAX_ARTICLES, FeedLimits.DEFAULT_MAX_ARTICLES)
            .coerceIn(FeedLimits.MIN_MAX_ARTICLES, FeedLimits.MAX_MAX_ARTICLES),
        prefs.getInt(KEY_REFRESH_MINUTES, FeedLimits.DEFAULT_REFRESH_MINUTES)
            .coerceIn(FeedLimits.MIN_REFRESH_MINUTES, FeedLimits.MAX_REFRESH_MINUTES),
    )

    fun saveLimits(limits: FeedLimits) {
        prefs.edit()
            .putInt(KEY_MAX_ARTICLES, limits.maxArticles)
            .putInt(KEY_REFRESH_MINUTES, limits.refreshMinutes)
            .apply()
    }

    fun isPlayNextOn(): Boolean = prefs.getBoolean(KEY_PLAY_NEXT, false)

    fun isPrefetchOn(): Boolean = prefs.getBoolean(KEY_PREFETCH, true)

    fun isIntroOn(): Boolean = prefs.getBoolean(KEY_INTRO, true)

    fun isAutoPlayOn(): Boolean = prefs.getBoolean(KEY_AUTO_PLAY, false)

    // ------------------------------------------------------------ Saved ---

    /** @throws IOException when the stored list is unreadable (reported, never shown as empty). */
    @Throws(IOException::class)
    fun saved(): List<SavedArticle> = try {
        ReadLater.decode(prefs.getString(KEY_SAVED, null))
    } catch (e: org.json.JSONException) {
        Log.e(TAG, "Saved Read later list is unreadable: ${e.message}", e)
        throw IOException("Your Read later list couldn't be read. Remove it and save the links again.", e)
    }

    /** Throws [IllegalArgumentException] with a message for links that can't be saved. */
    @Synchronized
    fun addSaved(url: String, title: String) {
        prefs.edit().putString(KEY_SAVED, ReadLater.encode(ReadLater.add(saved(), url, title, System.currentTimeMillis()))).apply()
    }

    @Synchronized
    fun removeSaved(url: String) {
        prefs.edit().putString(KEY_SAVED, ReadLater.encode(ReadLater.remove(saved(), url))).apply()
    }

    /** Clears an unreadable list so new links can be saved. */
    fun resetSaved() {
        prefs.edit().remove(KEY_SAVED).apply()
    }

    @Synchronized
    fun retitleSaved(url: String, title: String) {
        val list = try {
            saved()
        } catch (_: IOException) {
            return
        }
        if (list.none { it.url == url && it.title != title }) return
        prefs.edit().putString(KEY_SAVED, ReadLater.encode(ReadLater.retitle(list, url, title))).apply()
    }

    // ---------------------------------------------------------- History ---

    /** The listening history; an unreadable one is reported and starts over. */
    fun history(): List<HistoryEntry> = try {
        ListeningHistory.decode(prefs.getString(KEY_HISTORY, null))
    } catch (e: org.json.JSONException) {
        Log.e(TAG, "fallback=true attempted=read-history reason=\"${e.message}\" taken=empty-history", e)
        prefs.edit().remove(KEY_HISTORY).apply()
        historyWarning = "Your listening history couldn't be read, so it started over."
        emptyList()
    }

    /** Set once the history had to start over; shown in Recents and on the websites screen. */
    @Volatile
    var historyWarning: String? = null
        private set

    fun historyFor(url: String): HistoryEntry? = history().firstOrNull { it.url == url }

    @Synchronized
    fun recordProgress(url: String, title: String, siteLabel: String, offsetChars: Int, bodyChars: Int) {
        val updated = ListeningHistory.record(
            history(), url, title, siteLabel, offsetChars, bodyChars, System.currentTimeMillis(),
        )
        prefs.edit().putString(KEY_HISTORY, ListeningHistory.encode(updated)).apply()
    }

    @Synchronized
    fun markFinished(url: String) {
        prefs.edit().putString(
            KEY_HISTORY, ListeningHistory.encode(ListeningHistory.finish(history(), url, System.currentTimeMillis())),
        ).apply()
    }

    // ------------------------------------------------- Article actions ---
    // The article menu's actions (phone home screen). Each returns what Undo needs.

    /** Told after an article action changed marks or lists, so open lists reload. */
    fun interface ItemsListener {
        fun onItemsChanged()
    }

    private val itemsListeners = CopyOnWriteArrayList<ItemsListener>()

    fun addItemsListener(listener: ItemsListener) {
        itemsListeners += listener
    }

    fun removeItemsListener(listener: ItemsListener) {
        itemsListeners -= listener
    }

    fun notifyItemsChanged() = itemsChanged()

    private fun itemsChanged() {
        itemsListeners.forEach { it.onItemsChanged() }
    }

    /** Marks [url] heard; returns its previous history entry (null when it had none). */
    @Synchronized
    fun markHeard(url: String, title: String, siteLabel: String): HistoryEntry? {
        val history = history()
        val previous = history.firstOrNull { it.url == url }
        val updated = ListeningHistory.markHeard(history, url, title, siteLabel, System.currentTimeMillis())
        prefs.edit().putString(KEY_HISTORY, ListeningHistory.encode(updated)).apply()
        itemsChanged()
        return previous
    }

    /** Forgets [url] (unheard, no resume point); returns the removed entry for Undo. */
    @Synchronized
    fun forgetHistory(url: String): HistoryEntry? {
        val history = history()
        val previous = history.firstOrNull { it.url == url } ?: return null
        prefs.edit().putString(KEY_HISTORY, ListeningHistory.encode(ListeningHistory.forget(history, url))).apply()
        itemsChanged()
        return previous
    }

    /** Undo for [markHeard] / [forgetHistory]: puts [previous] back, or forgets [url] when null. */
    @Synchronized
    fun restoreHistory(url: String, previous: HistoryEntry?) {
        val history = history()
        val updated = if (previous == null) {
            ListeningHistory.forget(history, url)
        } else {
            ListeningHistory.restore(history, previous)
        }
        prefs.edit().putString(KEY_HISTORY, ListeningHistory.encode(updated)).apply()
        itemsChanged()
    }

    /** Removes [url] from Saved; returns the removed row for Undo (null when it wasn't there). */
    @Synchronized
    fun takeSaved(url: String): SavedArticle? {
        val list = saved()
        val previous = list.firstOrNull { it.url == url } ?: return null
        prefs.edit().putString(KEY_SAVED, ReadLater.encode(ReadLater.remove(list, url))).apply()
        itemsChanged()
        return previous
    }

    /** Undo for [takeSaved]. */
    @Synchronized
    fun restoreSaved(article: SavedArticle) {
        prefs.edit().putString(KEY_SAVED, ReadLater.encode(ReadLater.restore(saved(), article))).apply()
        itemsChanged()
    }

    /**
     * The hidden addresses. An unreadable list is reported and thrown; the lists then
     * show everything with a notice instead of guessing.
     */
    @Throws(IOException::class)
    fun hidden(): Set<String> = try {
        HiddenArticles.decode(prefs.getString(KEY_HIDDEN, null)).toSet()
    } catch (e: org.json.JSONException) {
        Log.e(TAG, "Hidden articles list is unreadable: ${e.message}", e)
        throw IOException("Your hidden articles list couldn't be read, so hidden articles show again.", e)
    }

    /** Hides [url] from the website lists and Latest (the car's too). */
    @Synchronized
    fun hide(url: String) {
        val list = try {
            HiddenArticles.decode(prefs.getString(KEY_HIDDEN, null))
        } catch (e: org.json.JSONException) {
            // Starting over loses nothing visible: an unreadable list hides nothing already.
            Log.w(TAG, "fallback=true attempted=read-hidden reason=\"${e.message}\" taken=new-hidden-list", e)
            emptyList()
        }
        prefs.edit().putString(KEY_HIDDEN, HiddenArticles.encode(HiddenArticles.hide(list, url))).apply()
        itemsChanged()
    }

    /** Undo for [hide]. */
    @Synchronized
    fun unhide(url: String) {
        val list = HiddenArticles.decode(prefs.getString(KEY_HIDDEN, null))
        prefs.edit().putString(KEY_HIDDEN, HiddenArticles.encode(HiddenArticles.unhide(list, url))).apply()
        itemsChanged()
    }

    fun setPlayNext(on: Boolean) {
        prefs.edit().putBoolean(KEY_PLAY_NEXT, on).apply()
    }

    /** Blocking network load; call off the main thread. */
    @Throws(IOException::class)
    @Synchronized
    fun headlines(site: FeedSite, force: Boolean = false): LoadedHeadlines {
        val limits = limits()
        seedCached(site, limits)
        val loaded = try {
            cache.load(site, limits, force)
        } catch (error: IOException) {
            Log.e(TAG, "Feed refresh failed site=${site.id}", error)
            setRefreshError(site.id, "Feed refresh failed. Open this feed and tap Refresh to retry.")
            throw error
        }
        setRefreshError(site.id, if (loaded.stale) loaded.notices.firstOrNull() else null)
        ReadingStore(appContext).use { store ->
            if (!loaded.stale) store.saveFeed(site, limits.maxArticles, loaded)
        }
        if (!loaded.stale && prefs.getLong(KEY_UPDATED_PREFIX + site.id, 0L) != loaded.fetchedAtMs) {
            prefs.edit().putLong(KEY_UPDATED_PREFIX + site.id, loaded.fetchedAtMs).apply()
        }
        return loaded
    }

    fun setRefreshError(siteId: String, message: String?) {
        prefs.edit().putString("feedRefreshError:$siteId", message).apply()
    }

    fun cachedHeadlineRefreshNotice(siteId: String): String? = cache.refreshNotice(siteId)
        ?: prefs.getString("feedRefreshError:$siteId", null)

    fun titleFor(url: String): String? = cache.titleFor(url)

    /** [siteId]'s last loaded titles (what the car just listed), without the network. */
    /** Restores a successful snapshot without a network request, retaining any refresh warning. */
    private fun seedCached(site: FeedSite, limits: FeedLimits) {
        if (cache.peek(site.id) != null) return
        ReadingStore(appContext).use { store ->
            store.loadFeed(site, limits.maxArticles)?.let { loaded ->
                val failure = prefs.getString("feedRefreshError:${site.id}", null)
                cache.seed(site, limits, if (failure == null) loaded else loaded.copy(stale = true, notices = listOf(failure) + loaded.notices))
            }
        }
    }

    fun cachedHeadlines(siteId: String): List<Headline>? {
        if (cache.peek(siteId) == null) site(siteId)?.let { seedCached(it, limits()) }
        return cache.peek(siteId)
    }
}
