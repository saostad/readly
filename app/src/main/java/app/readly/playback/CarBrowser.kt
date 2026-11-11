package app.readly.playback

import android.content.SharedPreferences
import android.media.MediaDescription
import android.media.browse.MediaBrowser.MediaItem
import android.os.Bundle
import android.os.Handler
import android.os.Process
import android.service.media.MediaBrowserService
import android.util.Log
import app.readly.feeds.FeedRepository
import app.readly.feeds.FeedSites
import app.readly.feeds.HeadlineSource
import app.readly.feeds.VoiceSearch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The Android Auto list behind [ReaderService]: the saved websites at the top, each
 * site's article titles under it, and a tap on a title reads it aloud.
 *
 * Media ids: [ROOT], `site:<id>`, `retry:<id>` (refetch after an error), and
 * `article:<url>`. Titles load on their own thread; errors become a visible list item
 * (tap it to try again) instead of an empty list.
 */
class CarBrowser(
    private val feeds: FeedRepository,
    private val main: Handler,
    private val notifyChildrenChanged: (String) -> Unit,
) {
    companion object {
        const val ROOT = "root"
        private const val EMPTY_ROOT = "empty"
        private const val SITE = "site:"
        private const val RETRY = "retry:"
        private const val ARTICLE = "article:"
        private const val NO_SITES = "nosites"
        private const val TAG = "Readly"

        // Android Auto content-style hints (protocol keys): show lists, not grids.
        private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val CONTENT_STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val CONTENT_STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val CONTENT_STYLE_LIST = 1

        /** Apps allowed to browse the saved websites: Android Auto, the Assistant, car media UIs. */
        private val TRUSTED_CLIENTS = setOf(
            "com.google.android.projection.gearhead",
            "com.google.android.carassistant",
            "com.google.android.googlequicksearchbox",
            "com.android.car.media",
            "com.android.systemui",
        )

        /** An article row's id: `article:<siteId>|<url>` (site ids never contain `|`). */
        fun articleId(siteId: String, url: String): String = "$ARTICLE$siteId|$url"

        /** The site id and address in an article row's id, or null for any other id. */
        fun parseArticle(mediaId: String?): Pair<String, String>? {
            val rest = mediaId?.takeIf { it.startsWith(ARTICLE) }?.removePrefix(ARTICLE) ?: return null
            val siteId = rest.substringBefore('|', "")
            val url = rest.substringAfter('|', "")
            return if (siteId.isBlank() || url.isBlank()) null else siteId to url
        }
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "readly-car") }

    /** Kept as a field: SharedPreferences holds listeners weakly. */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == FeedRepository.KEY_SITES) notifyChildrenChanged(ROOT)
    }

    init {
        feeds.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    fun close() {
        feeds.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        executor.shutdownNow()
    }

    fun root(clientPackage: String, clientUid: Int): MediaBrowserService.BrowserRoot {
        val extras = Bundle().apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(CONTENT_STYLE_BROWSABLE, CONTENT_STYLE_LIST)
            putInt(CONTENT_STYLE_PLAYABLE, CONTENT_STYLE_LIST)
        }
        val trusted = clientUid == Process.myUid() || clientUid == Process.SYSTEM_UID ||
            clientPackage in TRUSTED_CLIENTS
        if (!trusted) {
            // Still connectable for play/pause, but the saved websites stay private.
            Log.w(TAG, "Media browser client $clientPackage isn't trusted; showing an empty list")
            return MediaBrowserService.BrowserRoot(EMPTY_ROOT, extras)
        }
        return MediaBrowserService.BrowserRoot(ROOT, extras)
    }

    fun loadChildren(parentId: String, result: MediaBrowserService.Result<MutableList<MediaItem>>) {
        when {
            parentId == ROOT -> {
                result.detach()
                executor.execute { send(result, siteItems()) }
            }
            parentId.startsWith(SITE) || parentId.startsWith(RETRY) -> {
                val force = parentId.startsWith(RETRY)
                val id = parentId.substringAfter(':')
                result.detach()
                executor.execute { send(result, articleItems(id, force)) }
            }
            else -> result.sendResult(mutableListOf())
        }
    }

    /** Finds the article for a voice request off the main thread; [onPick] runs on main. */
    fun search(query: String?, onPick: (VoiceSearch.Pick) -> Unit) {
        executor.execute {
            val pick = try {
                VoiceSearch.pick(query, feeds.sites()) { site -> feeds.headlines(site).list.headlines }
            } catch (e: Exception) {
                Log.e(TAG, "Android Auto voice search failed", e)
                VoiceSearch.Pick.Failure(e.message ?: "Couldn't search your websites.")
            }
            main.post { onPick(pick) }
        }
    }

    private fun send(result: MediaBrowserService.Result<MutableList<MediaItem>>, items: MutableList<MediaItem>) {
        main.post {
            try {
                result.sendResult(items)
            } catch (e: IllegalStateException) {
                // The car left this screen before the list arrived.
                Log.w(TAG, "Android Auto list result dropped: ${e.message}")
            }
        }
    }

    private fun siteItems(): MutableList<MediaItem> {
        val sites = try {
            feeds.sites()
        } catch (e: Exception) {
            Log.e(TAG, "Android Auto couldn't read the saved websites", e)
            return mutableListOf(browsable(NO_SITES, "Couldn't read your websites", e.message))
        }
        if (sites.isEmpty()) {
            return mutableListOf(
                browsable(
                    NO_SITES,
                    "No websites yet",
                    "On your phone: Readly → Voice & speed → Android Auto websites",
                ),
            )
        }
        return sites.mapTo(mutableListOf()) { site -> browsable(SITE + site.id, site.label(), FeedSites.hostOf(site.url)) }
    }

    private fun articleItems(siteId: String, force: Boolean): MutableList<MediaItem> {
        val site = feeds.site(siteId)
            ?: return mutableListOf(browsable(NO_SITES, "This website was removed", "Go back to see your websites"))
        val loaded = try {
            feeds.headlines(site, force)
        } catch (e: Exception) {
            Log.e(TAG, "Android Auto couldn't load titles for ${FeedSites.hostOf(site.url)} (site=${site.id})", e)
            return mutableListOf(
                browsable(RETRY + site.id, "Couldn't load ${site.label()} · tap to retry", e.message ?: e.javaClass.simpleName),
            )
        }
        val guessed = loaded.list.source == HeadlineSource.PAGE_LINKS && loaded.list.notice != null
        val items = mutableListOf<MediaItem>()
        if (loaded.stale) {
            // The stale-list notice is the first row, and tapping it retries.
            items += browsable(RETRY + site.id, "Showing an older list · tap to refresh", loaded.notices.first())
        }
        for (headline in loaded.list.headlines) {
            val subtitle = listOfNotNull(
                if (guessed) "Guessed from page links" else null,
                headline.published?.take(40),
            ).joinToString(" · ").ifBlank { FeedSites.hostOf(headline.url) }
            items += MediaItem(
                MediaDescription.Builder()
                    .setMediaId(articleId(site.id, headline.url))
                    .setTitle(headline.title)
                    .setSubtitle(subtitle)
                    .build(),
                MediaItem.FLAG_PLAYABLE,
            )
        }
        return items
    }

    private fun browsable(id: String, title: String, subtitle: String?): MediaItem =
        MediaItem(
            MediaDescription.Builder().setMediaId(id).setTitle(title).setSubtitle(subtitle).build(),
            MediaItem.FLAG_BROWSABLE,
        )
}
