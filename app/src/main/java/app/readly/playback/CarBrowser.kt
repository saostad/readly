package app.readly.playback

import android.content.SharedPreferences
import android.media.MediaDescription
import android.media.browse.MediaBrowser.MediaItem
import android.os.Bundle
import android.os.Handler
import android.os.Process
import android.service.media.MediaBrowserService
import android.util.Log
import app.readly.feeds.CarEntry
import app.readly.feeds.FeedRepository
import app.readly.feeds.FeedSites
import app.readly.feeds.HeadlineSource
import app.readly.feeds.HistoryEntry
import app.readly.feeds.LatestMix
import app.readly.feeds.VoiceSearch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The Android Auto screens behind [ReaderService]. Four tabs: **Websites** (the saved
 * sites as tiles, each opening its article titles), **Latest** (every site's newest
 * articles taking turns), **Saved** (links shared to Read later) and **Recents**
 * (what you listened to, with half-finished ones first to resume). Every list starts
 * with a Shuffle row. Rows carry heard / part-heard marks from the listening history.
 *
 * Media ids: [ROOT], the tab ids, [RECENT_ROOT] (Android Auto's "continue listening"),
 * `site:<id>`, `retry:<listId>`, `article:<listId>|<url>` and `shuffle:<listId>`,
 * where a list id is a site id or a tab id. Lists load on their own thread; errors
 * become a visible row (tap it to try again) instead of an empty list.
 */
class CarBrowser(
    private val feeds: FeedRepository,
    private val main: Handler,
    private val notifyChildrenChanged: (String) -> Unit,
) {
    companion object {
        const val ROOT = "root"
        const val WEBSITES = "websites"
        const val LATEST = LatestMix.LATEST_LIST
        const val SAVED = "saved"
        const val RECENTS = "recents"

        /** The root Android Auto asks for to offer "continue listening" at startup. */
        const val RECENT_ROOT = "recentroot"

        private const val EMPTY_ROOT = "empty"
        private const val SITE = "site:"
        private const val RETRY = "retry:"
        private const val ARTICLE = "article:"
        private const val SHUFFLE = "shuffle:"
        private const val NOTICE = "notice"
        private const val TAG = "Readly"

        // Android Auto content-style hints (protocol keys): websites as a grid, articles as a list.
        private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val CONTENT_STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val CONTENT_STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val CONTENT_STYLE_LIST = 1
        private const val CONTENT_STYLE_GRID = 2

        // Heard marks (protocol keys and values shared with androidx.media).
        private const val PLAYBACK_STATUS = "android.media.extra.PLAYBACK_STATUS"
        private const val STATUS_NOT_PLAYED = 0
        private const val STATUS_PARTIALLY_PLAYED = 1
        private const val STATUS_FULLY_PLAYED = 2
        private const val COMPLETION_PERCENTAGE = "androidx.media.MediaItem.Extras.COMPLETION_PERCENTAGE"

        /** Readly's own row extra: the article's site name (for the phone's article menu). */
        const val EXTRA_SITE_LABEL = "app.readly.SITE_LABEL"

        /** Apps allowed to browse the saved websites: Android Auto, the Assistant, car media UIs. */
        val TRUSTED_CLIENTS = setOf(
            "com.google.android.projection.gearhead",
            "com.google.android.carassistant",
            "com.google.android.googlequicksearchbox",
            "com.android.car.media",
            "com.android.systemui",
        )

        /** True for a "tap to retry" row: opening it reloads its list from the network. */
        fun isRetry(mediaId: String): Boolean = mediaId.startsWith(RETRY)

        /** True for an information row ("No websites yet", errors) that leads nowhere. */
        fun isNotice(mediaId: String): Boolean = mediaId == NOTICE

                /** An article row's id: `article:<listId>|<url>` (list ids never contain `|`). */
        fun articleId(listId: String, url: String): String = "$ARTICLE$listId|$url"

        /** The list id and address in an article row's id, or null for any other id. */
        fun parseArticle(mediaId: String?): Pair<String, String>? {
            val rest = mediaId?.takeIf { it.startsWith(ARTICLE) }?.removePrefix(ARTICLE) ?: return null
            val listId = rest.substringBefore('|', "")
            val url = rest.substringAfter('|', "")
            return if (listId.isBlank() || url.isBlank()) null else listId to url
        }

        /** The car folder that shows list [listId] (to refresh its heard marks). */
        fun parentOf(listId: String): String = when (listId) {
            LATEST, SAVED, RECENTS, RECENT_ROOT -> listId
            else -> SITE + listId
        }

        /** The list a Shuffle row plays, or null for any other id. */
        fun parseShuffle(mediaId: String?): String? =
            mediaId?.takeIf { it.startsWith(SHUFFLE) }?.removePrefix(SHUFFLE)?.ifBlank { null }

        /** Heard mark for a row: [STATUS_NOT_PLAYED] etc. and the fraction heard. */
        fun completion(entry: HistoryEntry?): Pair<Int, Double> = when {
            entry == null -> STATUS_NOT_PLAYED to 0.0
            entry.finished -> STATUS_FULLY_PLAYED to 1.0
            entry.offsetChars > 0 -> STATUS_PARTIALLY_PLAYED to entry.fraction
            else -> STATUS_NOT_PLAYED to 0.0
        }
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "readly-car") }

    /** What each list showed the car last, so a tap, ⏭ or Shuffle uses the same order. */
    private val listed = ConcurrentHashMap<String, List<CarEntry>>()

    /** Kept as a field: SharedPreferences holds listeners weakly. */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            FeedRepository.KEY_SITES -> {
                notifyChildrenChanged(WEBSITES)
                notifyChildrenChanged(LATEST)
            }
            FeedRepository.KEY_SAVED -> notifyChildrenChanged(SAVED)
        }
    }

    /** An article action (heard, hidden, removed) can change every open list's rows. */
    private val itemsListener = FeedRepository.ItemsListener {
        main.post {
            listOf(LATEST, SAVED, RECENTS, RECENT_ROOT).forEach(notifyChildrenChanged)
            val sites = try {
                feeds.sites()
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't refresh the website lists after an article change", e)
                emptyList()
            }
            sites.forEach { notifyChildrenChanged(SITE + it.id) }
            notifyChildrenChanged(WEBSITES)
        }
    }

    init {
        feeds.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        feeds.addItemsListener(itemsListener)
    }

    fun close() {
        feeds.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        feeds.removeItemsListener(itemsListener)
        executor.shutdownNow()
    }

    fun isTrusted(clientPackage: String, clientUid: Int): Boolean =
        clientUid == Process.myUid() || clientUid == Process.SYSTEM_UID || clientPackage in TRUSTED_CLIENTS

    fun root(clientPackage: String, clientUid: Int, rootHints: Bundle?): MediaBrowserService.BrowserRoot {
        val extras = Bundle().apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(CONTENT_STYLE_BROWSABLE, CONTENT_STYLE_GRID)
            putInt(CONTENT_STYLE_PLAYABLE, CONTENT_STYLE_LIST)
        }
        if (!isTrusted(clientPackage, clientUid)) {
            // Still connectable for play/pause, but the saved websites stay private.
            Log.w(TAG, "Media browser client $clientPackage isn't trusted; showing an empty list")
            return MediaBrowserService.BrowserRoot(EMPTY_ROOT, extras)
        }
        if (rootHints?.getBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT) == true) {
            extras.putBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT, true)
            return MediaBrowserService.BrowserRoot(RECENT_ROOT, extras)
        }
        return MediaBrowserService.BrowserRoot(ROOT, extras)
    }

    fun loadChildren(parentId: String, result: MediaBrowserService.Result<MutableList<MediaItem>>) {
        when {
            // Top-level folders become the car's tabs (at most four).
            parentId == ROOT -> result.sendResult(
                mutableListOf(
                    browsable(WEBSITES, "Websites", null, childrenStyle = CONTENT_STYLE_GRID),
                    browsable(LATEST, "Latest", null, childrenStyle = CONTENT_STYLE_LIST),
                    browsable(SAVED, "Saved", null, childrenStyle = CONTENT_STYLE_LIST),
                    browsable(RECENTS, "Recents", null, childrenStyle = CONTENT_STYLE_LIST),
                ),
            )
            parentId == RECENT_ROOT -> result.sendResult(recentRootItems())
            else -> {
                result.detach()
                executor.execute { send(result, itemsFor(parentId)) }
            }
        }
    }

    private fun itemsFor(parentId: String): MutableList<MediaItem> = when {
        parentId == WEBSITES -> siteItems()
        parentId == LATEST -> latestItems(force = false)
        parentId == SAVED -> savedItems()
        parentId == RECENTS -> recentItems()
        parentId == RETRY + LATEST -> latestItems(force = true)
        parentId.startsWith(SITE) -> articleItems(parentId.removePrefix(SITE), force = false)
        parentId.startsWith(RETRY) -> articleItems(parentId.removePrefix(RETRY), force = true)
        else -> mutableListOf()
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

    /**
     * The entries of [listId] in the order the car showed them, or (after a restart)
     * rebuilt without the network: saved links, the history, a site's cached titles.
     */
    fun entriesFor(listId: String): List<CarEntry>? {
        listed[listId]?.let { return it }
        return when (listId) {
            SAVED -> savedEntries()
            RECENTS, RECENT_ROOT -> recentEntries()
            LATEST -> null
            else -> feeds.site(listId)?.let { site ->
                val hidden = hiddenOrNotice().first
                feeds.cachedHeadlines(listId)?.filterNot { it.url in hidden }?.map { CarEntry(listId, it, site.label()) }
            }
        }
    }

    /** The list's name for messages and the car's Up next title. */
    fun listTitle(listId: String): String = when (listId) {
        LATEST -> "Latest"
        SAVED -> "Saved"
        RECENTS, RECENT_ROOT -> "Recents"
        else -> feeds.site(listId)?.label() ?: "Readly"
    }

    /**
     * The next unheard article for auto-play: the first one in the first website that
     * has one, loading lists as needed. Blocking; [onPick] runs on main (null = none).
     */
    fun nextUnheard(onPick: (CarEntry?) -> Unit) {
        executor.execute {
            val heard = feeds.history().associateBy { it.url }
            val hidden = hiddenOrNotice().first
            val pick = try {
                feeds.sites().firstNotNullOfOrNull { site ->
                    val headlines = try {
                        feeds.headlines(site).list.headlines
                    } catch (e: Exception) {
                        Log.w(TAG, "Auto-play skipped ${FeedSites.hostOf(site.url)}: ${e.message}")
                        emptyList()
                    }
                    val entries = headlines.filterNot { it.url in hidden }.map { CarEntry(site.id, it, site.label()) }
                    listed[site.id] = entries
                    entries.firstOrNull { heard[it.headline.url]?.finished != true }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto-play couldn't read your websites", e)
                null
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

    /**
     * The hidden addresses, plus a notice row when the stored list is unreadable (then
     * nothing is hidden, and the row says so instead of silently showing them).
     */
    private fun hiddenOrNotice(): Pair<Set<String>, MediaItem?> = try {
        feeds.hidden() to null
    } catch (e: Exception) {
        Log.w(TAG, "fallback=true attempted=read-hidden reason=\"${e.message}\" taken=show-all")
        emptySet<String>() to browsable(NOTICE, "Hidden articles are showing", e.message)
    }

    // ------------------------------------------------------------ tabs ---

    private fun siteItems(): MutableList<MediaItem> {
        val sites = try {
            feeds.sites()
        } catch (e: Exception) {
            Log.e(TAG, "Android Auto couldn't read the saved websites", e)
            return mutableListOf(browsable(NOTICE, "Couldn't read your websites", e.message))
        }
        if (sites.isEmpty()) {
            return mutableListOf(
                browsable(NOTICE, "No websites yet", "On your phone: Readly → Voice & speed → Android Auto websites"),
            )
        }
        val heard = feeds.history().associateBy { it.url }
        val hidden = hiddenOrNotice().first
        return sites.mapTo(mutableListOf()) { site ->
            // "3 new" when the titles are already loaded; never a network call per tile.
            val unheard = feeds.cachedHeadlines(site.id)
                ?.count { heard[it.url]?.finished != true && it.url !in hidden }
            browsable(
                SITE + site.id,
                site.label(),
                if (unheard != null) "$unheard new" else FeedSites.hostOf(site.url),
                ArtworkProvider.siteUri(site.label()),
                // Inside a site: rows (a retry row is browsable too), never tiles.
                childrenStyle = CONTENT_STYLE_LIST,
            )
        }
    }

    private fun articleItems(siteId: String, force: Boolean): MutableList<MediaItem> {
        val site = feeds.site(siteId)
            ?: return mutableListOf(browsable(NOTICE, "This website was removed", "Go back to see your websites"))
        val loaded = try {
            feeds.headlines(site, force)
        } catch (e: Exception) {
            Log.e(TAG, "Android Auto couldn't load titles for ${FeedSites.hostOf(site.url)} (site=${site.id})", e)
            return mutableListOf(
                browsable(RETRY + site.id, "Couldn't load ${site.label()} · tap to retry", e.message ?: e.javaClass.simpleName),
            )
        }
        val guessed = loaded.list.source == HeadlineSource.PAGE_LINKS && loaded.list.notice != null
        val (hidden, hiddenNotice) = hiddenOrNotice()
        val entries = loaded.list.headlines.filterNot { it.url in hidden }.map { CarEntry(site.id, it, site.label()) }
        listed[site.id] = entries
        val items = listOfNotNull(hiddenNotice).toMutableList<MediaItem>()
        if (loaded.stale) {
            // The stale-list notice is the first row, and tapping it retries.
            items += browsable(RETRY + site.id, "Showing an older list · tap to refresh", loaded.notices.first())
        }
        items += articleRows(site.id, entries) { entry ->
            listOfNotNull(
                if (guessed) "Guessed from page links" else null,
                entry.headline.published?.take(40),
            ).joinToString(" · ").ifBlank { FeedSites.hostOf(entry.headline.url) }
        }
        return items
    }

    private fun latestItems(force: Boolean): MutableList<MediaItem> {
        val sites = try {
            feeds.sites()
        } catch (e: Exception) {
            Log.e(TAG, "Android Auto couldn't read the saved websites for Latest", e)
            return mutableListOf(browsable(NOTICE, "Couldn't read your websites", e.message))
        }
        if (sites.isEmpty()) {
            return mutableListOf(browsable(NOTICE, "No websites yet", "Add websites on your phone to fill Latest"))
        }
        val failed = mutableListOf<String>()
        val stale = mutableListOf<String>()
        val (hidden, hiddenNotice) = hiddenOrNotice()
        val lists = sites.mapNotNull { site ->
            try {
                val loaded = feeds.headlines(site, force)
                if (loaded.stale) stale += site.label()
                site.label() to loaded.list.headlines.filterNot { it.url in hidden }
            } catch (e: Exception) {
                Log.e(TAG, "Latest couldn't load ${FeedSites.hostOf(site.url)} (site=${site.id})", e)
                failed += site.label()
                null
            }
        }
        val entries = LatestMix.interleave(lists, feeds.limits().maxArticles)
        listed[LATEST] = entries
        val items = listOfNotNull(hiddenNotice).toMutableList<MediaItem>()
        if (failed.isNotEmpty() || stale.isNotEmpty()) {
            val subtitle = listOfNotNull(
                failed.takeIf { it.isNotEmpty() }?.let { "Couldn't load ${it.joinToString(", ")}" },
                stale.takeIf { it.isNotEmpty() }?.let { "older list for ${it.joinToString(", ")}" },
            ).joinToString(" · ")
            items += browsable(RETRY + LATEST, "Some websites are missing or old · tap to retry", subtitle)
        }
        items += articleRows(LATEST, entries) { it.siteLabel }
        return items
    }

    private fun savedEntries(): List<CarEntry>? = try {
        feeds.saved().map { CarEntry(SAVED, app.readly.feeds.Headline(it.title, it.url), FeedSites.hostOf(it.url)) }
    } catch (e: Exception) {
        Log.e(TAG, "Android Auto couldn't read the Read later list", e)
        null
    }

    private fun savedItems(): MutableList<MediaItem> {
        val entries = try {
            feeds.saved().map { CarEntry(SAVED, app.readly.feeds.Headline(it.title, it.url), FeedSites.hostOf(it.url)) }
        } catch (e: Exception) {
            return mutableListOf(browsable(NOTICE, "Couldn't read your Saved list", e.message))
        }
        if (entries.isEmpty()) {
            return mutableListOf(
                browsable(NOTICE, "Nothing saved yet", "On your phone, share a link and choose Read later"),
            )
        }
        listed[SAVED] = entries
        return articleRows(SAVED, entries) { it.siteLabel }
    }

    private fun recentEntries(): List<CarEntry> {
        val history = feeds.history()
        // Half-finished first (to resume), then the rest, each newest first.
        return (history.filter { it.resumable } + history.filterNot { it.resumable })
            .take(feeds.limits().maxArticles)
            .map { CarEntry(RECENTS, app.readly.feeds.Headline(it.title.ifBlank { FeedSites.hostOf(it.url) }, it.url), it.siteLabel) }
    }

    private fun recentItems(): MutableList<MediaItem> {
        val entries = recentEntries()
        feeds.historyWarning?.let { warning ->
            if (entries.isEmpty()) return mutableListOf(browsable(NOTICE, "Listening history was reset", warning))
        }
        if (entries.isEmpty()) {
            return mutableListOf(browsable(NOTICE, "Nothing heard yet", "Articles you listen to in the car show up here"))
        }
        listed[RECENTS] = entries
        return articleRows(RECENTS, entries) { it.siteLabel.ifBlank { FeedSites.hostOf(it.headline.url) } }
    }

    /** "Continue listening": the newest half-finished article, playable from the car's start screen. */
    private fun recentRootItems(): MutableList<MediaItem> {
        val entry = feeds.history().firstOrNull { it.resumable } ?: return mutableListOf()
        val carEntry = CarEntry(RECENT_ROOT, app.readly.feeds.Headline(entry.title, entry.url), entry.siteLabel)
        listed[RECENT_ROOT] = listOf(carEntry)
        return articleRows(RECENT_ROOT, listOf(carEntry), withShuffle = false) { it.siteLabel }
    }

    // ------------------------------------------------------------ rows ---

    private fun articleRows(
        listId: String,
        entries: List<CarEntry>,
        withShuffle: Boolean = true,
        subtitle: (CarEntry) -> String,
    ): MutableList<MediaItem> {
        val heard = feeds.history().associateBy { it.url }
        val rows = mutableListOf<MediaItem>()
        if (withShuffle && entries.size > 1) {
            rows += MediaItem(
                MediaDescription.Builder().setMediaId(SHUFFLE + listId).setTitle("Shuffle")
                    .setSubtitle("Play these ${entries.size} articles in a random order").build(),
                MediaItem.FLAG_PLAYABLE,
            )
        }
        for (entry in entries) {
            val history = heard[entry.headline.url]
            val (status, fraction) = completion(history)
            val mark = when (status) {
                STATUS_FULLY_PLAYED -> "Heard"
                STATUS_PARTIALLY_PLAYED -> "${(fraction * 100).toInt()}% heard"
                else -> null
            }
            rows += MediaItem(
                MediaDescription.Builder()
                    .setMediaId(articleId(listId, entry.headline.url))
                    .setTitle(entry.headline.title)
                    .setSubtitle(listOfNotNull(mark, subtitle(entry).ifBlank { null }).joinToString(" · "))
                    .setExtras(
                        Bundle().apply {
                            putInt(PLAYBACK_STATUS, status)
                            if (status == STATUS_PARTIALLY_PLAYED) putDouble(COMPLETION_PERCENTAGE, fraction)
                            putString(EXTRA_SITE_LABEL, entry.siteLabel)
                        },
                    )
                    .build(),
                MediaItem.FLAG_PLAYABLE,
            )
        }
        return rows
    }

    private fun browsable(
        id: String,
        title: String,
        subtitle: String?,
        icon: android.net.Uri? = null,
        childrenStyle: Int? = null,
    ): MediaItem {
        val extras = childrenStyle?.let { style ->
            Bundle().apply {
                putInt(CONTENT_STYLE_BROWSABLE, style)
                putInt(CONTENT_STYLE_PLAYABLE, CONTENT_STYLE_LIST)
            }
        }
        return MediaItem(
            MediaDescription.Builder().setMediaId(id).setTitle(title).setSubtitle(subtitle)
                .setIconUri(icon).setExtras(extras).build(),
            MediaItem.FLAG_BROWSABLE,
        )
    }
}
