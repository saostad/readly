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
