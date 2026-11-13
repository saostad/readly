package app.readly.playback

import app.readly.feeds.*
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class BrowseRefreshRequestsTest {
    private val site = FeedSite("news", "https://news.example/")
    private val limits = FeedLimits.DEFAULT

    @Test fun manualRequestBypassesFreshCacheButLiveUpdatesDoNotRepeatIt() {
        var calls = 0
        val cache = HeadlineCache({ _, _ ->
            calls++
            headlines("Revision $calls")
        }, { 1_000_000L }, { fail(it) })
        val requests = BrowseRefreshRequests()
        fun load(at: Long?) = cache.load(site, limits, requests.consume("site:news", at))
        assertEquals("Revision 1", load(null).list.headlines.single().title)
        assertEquals("Revision 2", load(10).list.headlines.single().title)
        repeat(3) { assertEquals("Revision 2", load(10).list.headlines.single().title) }
        assertEquals("Revision 3", load(20).list.headlines.single().title)
        assertEquals("Revision 3", load(10).list.headlines.single().title)
        assertEquals(3, calls)
    }

    @Test fun refreshFailuresRemainVisibleDuringSubsequentCachedUpdatesUntilSuccess() {
        var offline = false
        var calls = 0
        val warnings = mutableListOf<String>()
        val cache = HeadlineCache({ _, _ ->
            calls++
            if (offline) throw IOException("private upstream failure details")
            headlines("Revision $calls")
        }, { 1_000_000L }, warnings::add, { "original fetch time" })
        val requests = BrowseRefreshRequests()
        val first = cache.load(site, limits)
        offline = true
        val refresh = cache.load(site, limits, requests.consume("site:news", 10))
        assertTrue(refresh.stale)
        val update = cache.load(site, limits, requests.consume("site:news", 10))
        assertTrue("A normal live update must not hide the failed manual refresh", update.stale)
        assertEquals(first.list, update.list)
        assertEquals(first.fetchedAtMs, update.fetchedAtMs)
        assertTrue(update.notices.first().contains("original fetch time"))
        assertFalse(update.notices.first().contains("private upstream failure details"))
        assertEquals(update.notices.first(), cache.refreshNotice(site.id))
        assertEquals(2, calls)
        assertTrue(warnings.all { "fallback=true" in it })
        offline = false
        assertFalse(cache.load(site, limits, requests.consume("site:news", 20)).stale)
        assertNull(cache.refreshNotice(site.id))
    }

    @Test fun independentListsAndNewerRequestsAreHandledSeparately() {
        val requests = BrowseRefreshRequests()
        assertFalse(requests.consume("latest", null))
        assertTrue(requests.consume("latest", 20))
        assertFalse(requests.consume("latest", 19))
        assertTrue(requests.consume("websites", 19))
        assertTrue(requests.consume("latest", 21))
        assertThrows(IllegalArgumentException::class.java) { requests.consume("latest", 0) }
        assertThrows(IllegalArgumentException::class.java) { requests.consume("", 30) }
    }

    @Test fun retryRowsRefreshTheCanonicalScreen() {
        assertEquals(CarBrowser.LATEST, BrowseRefreshRequests.parent("retry:${CarBrowser.LATEST}"))
        assertEquals(CarBrowser.WEBSITES, BrowseRefreshRequests.parent("retry:websites"))
        assertEquals("site:news", BrowseRefreshRequests.parent("retry:news"))
        assertEquals("site:news", BrowseRefreshRequests.parent("site:news"))
        assertEquals(CarBrowser.SAVED, BrowseRefreshRequests.parent(CarBrowser.SAVED))
        assertThrows(IllegalArgumentException::class.java) { BrowseRefreshRequests.parent("retry:") }
    }

    private fun headlines(title: String) = HeadlineList("News", listOf(Headline(title, "https://news.example/story")),
        HeadlineSource.FEED, "https://news.example/rss", null)
}
