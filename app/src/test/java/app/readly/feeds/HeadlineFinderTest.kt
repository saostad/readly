package app.readly.feeds

import app.readly.input.FetchConnection
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HeadlineFinderTest {

    private val warnings = mutableListOf<String>()
    private val requested = mutableListOf<String>()

    private class FakeConnection(
        override val statusCode: Int,
        private val headers: Map<String, String> = emptyMap(),
        private val body: String = "",
    ) : FetchConnection {
        override val statusMessage: String = if (statusCode == 200) "OK" else "Not Found"
        override fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        override fun input(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun close() = Unit
    }

    /** Unknown addresses answer 404, like a real site without that feed path. */
    private fun finder(pages: Map<String, FakeConnection>) = HeadlineFinder(
        urlOpener = { url ->
            requested += url
            pages[url] ?: FakeConnection(404)
        },
        onWarn = warnings::add,
    )

    private fun html(body: String, head: String = "") =
        FakeConnection(200, mapOf("Content-Type" to "text/html; charset=utf-8"), "<html><head><title>Daily News</title>$head</head><body>$body</body></html>")

    private fun xml(body: String) = FakeConnection(200, mapOf("Content-Type" to "application/rss+xml"), body)

    private val rss = """<?xml version="1.0"?>
        <rss version="2.0"><channel><title>Daily News</title>
          <item><title>Council approves the new riverside park plan</title><link>https://news.example/2026/11/park</link><pubDate>Tue, 10 Nov 2026 08:00:00 GMT</pubDate></item>
          <item><title><![CDATA[Storm &amp; flood warnings for the coast]]></title><link>https://news.example/2026/11/storm</link></item>
          <item><title>Local team wins the regional final at home</title><guid>https://news.example/2026/11/final</guid></item>
        </channel></rss>"""

    @Test
    fun rssAddress_readsItsItems() {
        val list = finder(mapOf("https://news.example/rss" to xml(rss))).find("https://news.example/rss", FindMode.AUTO, 10)
        assertEquals(HeadlineSource.FEED, list.source)
        assertEquals("Daily News", list.siteTitle)
        assertEquals(
            listOf(
                "Council approves the new riverside park plan",
                "Storm & flood warnings for the coast",
                "Local team wins the regional final at home",
            ),
            list.headlines.map { it.title },
        )
        assertEquals("https://news.example/2026/11/final", list.headlines[2].url)
        assertEquals("Tue, 10 Nov 2026 08:00:00 GMT", list.headlines[0].published)
        assertNull(list.notice)
    }

    @Test
    fun maxArticles_limitsTheList() {
        val list = finder(mapOf("https://news.example/rss" to xml(rss))).find("https://news.example/rss", FindMode.AUTO, 2)
        assertEquals(2, list.headlines.size)
    }

    @Test
    fun homepage_followsTheAdvertisedAtomFeed() {
        val atom = """<?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom"><title>Tech Desk</title>
              <entry><title>New phone chips are faster and cooler</title><link rel="alternate" href="/tech/chips"/><updated>2026-11-10T08:00:00Z</updated></entry>
            </feed>"""
        val pages = mapOf(
            "https://news.example/" to html(
                "<p>home</p>",
                head = """<link rel="alternate" type="application/atom+xml" href="/atom.xml">""",
            ),
            "https://news.example/atom.xml" to FakeConnection(200, mapOf("Content-Type" to "application/atom+xml"), atom),
        )
        val list = finder(pages).find("https://news.example/", FindMode.AUTO, 10)
        assertEquals(HeadlineSource.LINKED_FEED, list.source)
        assertEquals("https://news.example/atom.xml", list.feedUrl)
        assertEquals("https://news.example/tech/chips", list.headlines.single().url)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun noAdvertisedFeed_triesCommonFeedPaths() {
        val pages = mapOf(
            "https://blog.example/world" to html("<p>no feed link</p>"),
            "https://blog.example/world/rss" to xml(rss),
        )
        val list = finder(pages).find("https://blog.example/world", FindMode.AUTO, 10)
        assertEquals(HeadlineSource.LINKED_FEED, list.source)
        assertEquals(listOf("https://blog.example/world", "https://blog.example/world/feed", "https://blog.example/world/rss"), requested)
    }

    private val newsPage = """
        <header><nav><a href="/world/a-long-navigation-link-that-looks-like-a-story">World news and more from every region today</a></nav></header>
        <main>
          <article><header><h2><a href="/2026/11/10/council-approves-riverside-park">Council approves the riverside park plan after a long debate</a></h2></header>
            <p>Teaser text.</p><a href="/2026/11/10/council-approves-riverside-park">Read more</a></article>
          <article><h3><a href="/politics/budget-vote-delayed-again-this-week?utm_source=home">Budget vote delayed again this week in the capital</a></h3></article>
          <div class="card"><a href="/science/123456"><h3>Scientists map the deep ocean floor in record detail</h3></a></div>
          <a href="/tag/politics">Everything you need to know about politics today</a>
          <a href="https://other.example/2026/11/10/elsewhere-story-here">A story on another website about something else</a>
          <a href="/2026/11/10/short">Short</a>
          <h3><a href="/politics/budget-vote-delayed-again-this-week/">Budget vote delayed again</a></h3>
        </main>
        <footer><a href="/2026/11/10/footer-story-about-the-company">Footer link about the company and its owners</a></footer>
        <aside><a href="/2026/11/10/most-read-sidebar-story-goes-here">Most read sidebar story goes here for everyone</a></aside>
    """

    @Test
    fun noFeedAnywhere_picksArticleLinksAndSaysSo() {
        val list = finder(mapOf("https://news.example/" to html(newsPage))).find("https://news.example/", FindMode.AUTO, 10)
        assertEquals(HeadlineSource.PAGE_LINKS, list.source)
        assertEquals(
            listOf(
                "Council approves the riverside park plan after a long debate",
                "Budget vote delayed again this week in the capital",
                "Scientists map the deep ocean floor in record detail",
            ),
            list.headlines.map { it.title },
        )
        assertEquals("https://news.example/science/123456", list.headlines[2].url)
        assertEquals(HeadlineFinder.PAGE_LINKS_NOTICE, list.notice)
        assertTrue(warnings.single().contains("fallback=true"))
        assertTrue(warnings.single().contains("taken=page-links"))
    }

    @Test
    fun pageLinksMode_skipsTheFeedWithoutAFallbackNotice() {
        val pages = mapOf(
            "https://news.example/" to html(
                newsPage,
                head = """<link rel="alternate" type="application/rss+xml" href="/rss">""",
            ),
            "https://news.example/rss" to xml(rss),
        )
        val list = finder(pages).find("https://news.example/", FindMode.PAGE_LINKS, 10)
        assertEquals(HeadlineSource.PAGE_LINKS, list.source)
        assertEquals(3, list.headlines.size)
        assertNull(list.notice)
        assertEquals(listOf("https://news.example/"), requested)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun brokenAdvertisedFeed_fallsBackToLinksWithTheReason() {
        val pages = mapOf(
            "https://news.example/" to html(newsPage, head = """<link rel="alternate" type="application/rss+xml" href="/broken">"""),
        )
        val list = finder(pages).find("https://news.example/", FindMode.AUTO, 10)
        assertEquals(HeadlineSource.PAGE_LINKS, list.source)
        assertTrue(warnings.single(), warnings.single().contains("https://news.example/broken failed"))
    }

    @Test
    fun redirect_isFollowed() {
        val pages = mapOf(
            "http://news.example/rss" to FakeConnection(301, mapOf("Location" to "https://news.example/rss")),
            "https://news.example/rss" to xml(rss),
        )
        assertEquals(3, finder(pages).find("http://news.example/rss", FindMode.AUTO, 10).headlines.size)
    }

    @Test
    fun pageWithoutArticles_failsWithAMessage() {
        val message = failureOf { finder(mapOf("https://news.example/" to html("<a href='/about'>About us</a>"))).find("https://news.example/", FindMode.AUTO, 10) }
        assertTrue(message, message.contains("Couldn't find any article links on news.example"))
    }

    @Test
    fun httpError_failsWithTheStatus() {
        val message = failureOf { finder(emptyMap()).find("https://news.example/", FindMode.AUTO, 10) }
        assertTrue(message, message.contains("HTTP 404"))
    }

    @Test
    fun emptyFeed_failsWithAMessage() {
        val empty = xml("<?xml version=\"1.0\"?><rss><channel><title>x</title></channel></rss>")
        val message = failureOf { finder(mapOf("https://news.example/rss" to empty)).find("https://news.example/rss", FindMode.AUTO, 10) }
        assertTrue(message, message.contains("has no articles"))
    }

    private fun failureOf(block: () -> Unit): String {
        try {
            block()
        } catch (e: IOException) {
            return e.message.orEmpty()
        }
        fail("expected an IOException")
        throw AssertionError()
    }
}
