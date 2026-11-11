package app.readly.feeds

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import app.readly.input.ArticleFetcher
import app.readly.playback.ReaderService
import java.io.IOException

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

        /** Open the next article in the list while one plays (default on). */
        const val KEY_PREFETCH = "autoPrefetch"

        /** Say the site, title and length before each car article (default on). */
        const val KEY_INTRO = "autoIntro"

        /** Start the next unheard article when Android Auto opens Readly (default off). */
        const val KEY_AUTO_PLAY = "autoPlayOnConnect"
        private const val TAG = "Readly"
    }

    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(ReaderService.PREFS_NAME, Context.MODE_PRIVATE)

    private val cache = HeadlineCache(
        finder = { site, max ->
            HeadlineFinder(ArticleFetcher::httpUrlOpener) { Log.w(TAG, "Android Auto headlines: $it") }
                .find(site.url, site.mode, max)
        },
        clock = System::currentTimeMillis,
        onWarn = { Log.w(TAG, "Android Auto headlines: $it") },
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
        cache.forget(id)
    }

    fun setMode(id: String, mode: FindMode) {
        save(sites().map { if (it.id == id) it.copy(mode = mode) else it })
        cache.forget(id)
    }

    private fun save(sites: List<FeedSite>) {
        prefs.edit().putString(KEY_SITES, FeedSites.encode(sites)).apply()
    }

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

    fun setPlayNext(on: Boolean) {
        prefs.edit().putBoolean(KEY_PLAY_NEXT, on).apply()
    }

    /** Blocking network load; call off the main thread. */
    @Throws(IOException::class)
    fun headlines(site: FeedSite, force: Boolean = false): LoadedHeadlines = cache.load(site, limits(), force)

    fun titleFor(url: String): String? = cache.titleFor(url)

    /** [siteId]'s last loaded titles (what the car just listed), without the network. */
    fun cachedHeadlines(siteId: String): List<Headline>? = cache.peek(siteId)
}
