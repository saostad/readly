package app.readly.feeds

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FeedSitesTest {

    @Test
    fun normalizeUrl_addsHttpsAndLowercasesTheHost() {
        assertEquals("https://news.example/", FeedSites.normalizeUrl("  News.Example "))
        assertEquals("https://news.example/world?x=1", FeedSites.normalizeUrl("news.example/world?x=1"))
        assertEquals("http://news.example:8080/rss", FeedSites.normalizeUrl("http://news.example:8080/rss"))
    }

    @Test
    fun normalizeUrl_rejectsWhatIsntAWebsite() {
        for (bad in listOf("", "   ", "ftp://news.example", "localhost", "two words.com", "javascript:alert(1)")) {
            try {
                FeedSites.normalizeUrl(bad)
                fail("expected $bad to be rejected")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun add_refusesTheSameAddressTwice() {
        val one = FeedSites.add(emptyList(), "news.example")
        try {
            FeedSites.add(one, "https://NEWS.example/")
            fail("expected a duplicate error")
        } catch (e: IllegalArgumentException) {
            assertEquals("That website is already in the list.", e.message)
        }
    }

    @Test
    fun encodeDecode_roundTripsAndSkipsDamagedEntries() {
        val sites = listOf(
            FeedSite("a", "https://news.example/", "News", FindMode.PAGE_LINKS),
            FeedSite("b", "https://www.blog.example/rss"),
        )
        assertEquals(sites, FeedSites.decode(FeedSites.encode(sites)) { fail(it) })
        val bad = mutableListOf<String>()
        val decoded = FeedSites.decode("""[{"id":"a","url":"https://x.example/"},{"id":""},{"url":"https://y.example/"}]""", bad::add)
        assertEquals(listOf("a"), decoded.map { it.id })
        assertEquals(2, bad.size)
        assertEquals("blog.example", sites[1].label())
    }

    @Test
    fun limits_validateTheirRange() {
        assertEquals(30, FeedLimits.parseMaxArticles(" 30 ").getOrThrow())
        assertTrue(FeedLimits.parseMaxArticles("0").isFailure)
        assertTrue(FeedLimits.parseMaxArticles("101").isFailure)
        assertTrue(FeedLimits.parseRefreshMinutes("abc").isFailure)
        assertEquals(1440, FeedLimits.parseRefreshMinutes("1440").getOrThrow())
    }
}

class HeadlineCacheTest {

    private var now = 1_000_000L
    private val warnings = mutableListOf<String>()
    private var calls = 0
    private var failWith: IOException? = null
    private var available = 50

    private val site = FeedSite("s", "https://news.example/")
    private val limits = FeedLimits(maxArticles = 10, refreshMinutes = 15)

    private val cache = HeadlineCache(
        finder = { _, max ->
            calls++
            failWith?.let { throw it }
            HeadlineList(
                "News",
                (1..minOf(max, available)).map { Headline("Story number $it here", "https://news.example/$it") },
                HeadlineSource.FEED,
                "https://news.example/rss",
                null,
            )
        },
        clock = { now },
        onWarn = warnings::add,
        formatTime = { "T$it" },
    )

    @Test
    fun freshList_comesFromTheCache() {
        cache.load(site, limits)
        now += 14 * 60_000L
        val second = cache.load(site, limits)
        assertEquals(1, calls)
        assertFalse(second.stale)
        assertEquals("Story number 3 here", cache.titleFor("https://news.example/3"))
    }

    @Test
    fun expiredOrForced_refetches() {
        cache.load(site, limits)
        now += 15 * 60_000L
        cache.load(site, limits)
        cache.load(site, limits, force = true)
        assertEquals(3, calls)
    }

    @Test
    fun raisedLimit_refetchesButLoweredLimitDoesnt() {
        cache.load(site, limits)
        assertEquals(5, cache.load(site, limits.copy(maxArticles = 5)).list.headlines.size)
        assertEquals(1, calls)
        assertEquals(20, cache.load(site, limits.copy(maxArticles = 20)).list.headlines.size)
        assertEquals(2, calls)
    }

    @Test
    fun shortList_coversAHigherLimit() {
        available = 3
        cache.load(site, limits)
        cache.load(site, limits.copy(maxArticles = 50))
        assertEquals(1, calls)
    }

    @Test
    fun changedAddress_refetches() {
        cache.load(site, limits)
        cache.load(site.copy(url = "https://news.example/world"), limits)
        assertEquals(2, calls)
    }

    @Test
    fun failedRefresh_showsTheOlderListAndSaysSo() {
        val first = cache.load(site, limits)
        now += 60 * 60_000L
        failWith = IOException("HTTP 503")
        val stale = cache.load(site, limits)
        assertTrue(stale.stale)
        assertEquals(first.fetchedAtMs, stale.fetchedAtMs)
        assertEquals("Couldn't refresh, so this is the list from T1000000.", stale.notices.first())
        assertTrue(warnings.single().contains("fallback=true"))
    }

    @Test
    fun failureWithoutAnOlderList_isThrown() {
        failWith = IOException("HTTP 503")
        try {
            cache.load(site, limits)
            fail("expected the failure")
        } catch (e: IOException) {
            assertEquals("HTTP 503", e.message)
        }
        assertTrue(warnings.isEmpty())
    }
}
