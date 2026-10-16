package app.readly.input

import app.readly.text.SourceFormat
import app.readly.text.TextSanitizer
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ArticleFetcherTest {

    private val warnings = mutableListOf<String>()

    private class FakeConnection(
        override val statusCode: Int,
        override val statusMessage: String = "",
        private val headers: Map<String, String> = emptyMap(),
        private val body: ByteArray = ByteArray(0),
    ) : FetchConnection {
        override fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        override fun input(): InputStream = ByteArrayInputStream(body)
        override fun close() = Unit
    }

    private fun fetcherFor(responses: Map<String, FakeConnection>): ArticleFetcher {
        val opener: (String) -> FetchConnection = { url ->
            responses[url] ?: throw IOException("unexpected URL in test: $url")
        }
        return ArticleFetcher(opener, warnings::add)
    }

    private fun resource(name: String): ByteArray =
        (javaClass.classLoader ?: ClassLoader.getSystemClassLoader())
            .getResourceAsStream(name)!!.use { it.readBytes() }

    private fun htmlPage(url: String, body: ByteArray, contentType: String = "text/html"): Pair<String, FakeConnection> =
        url to FakeConnection(200, "OK", mapOf("Content-Type" to contentType), body)

    private fun fetchFailsWith(fetcher: ArticleFetcher, url: String): String {
        try {
            fetcher.fetch(url)
        } catch (e: IOException) {
            return e.message ?: "(no message)"
        }
        fail("expected fetch of $url to fail")
        throw AssertionError("unreachable")
    }

    @Test
    fun fetch_newsArticle_extractsArticleWithoutClutter() {
        val url = "https://example.com/riverside-park"
        val fetcher = fetcherFor(mapOf(htmlPage(url, resource("article-news.html"))))
        val doc = fetcher.fetch(url)
        assertEquals(SourceFormat.HTML, doc.format)
        assertNull(doc.notice)
        assertTrue("title was: ${doc.title}", doc.title.contains("Riverside Park"))

        // This also proves readability4j runs on the forced jsoup 1.23.2.
        val clean = TextSanitizer.sanitize(doc.text, SourceFormat.HTML)
        assertTrue(clean.contains("voted six to three"))
        assertTrue(clean.contains("Maple Street entrance"))
        assertTrue(clean.contains("every dollar spent on green space"))
        assertTrue(clean.contains("the playground"))
        listOf(
            "Subscribe", "Cookie", "cookies", "World", "Business", "Weather",
            "FirstCommenter", "NightOwl99", "BuyCheapPillsNow", "rainy weekends",
            "legal links", "stadium",
        ).forEach { clutter ->
            assertFalse("clutter leaked through: $clutter", clean.contains(clutter))
        }
    }

    @Test
    fun fetch_shortPage_readsWholeBodyWithNotice() {
        val url = "https://example.com/tiny"
        val fetcher = fetcherFor(mapOf(htmlPage(url, resource("article-short.html"))))
        val doc = fetcher.fetch(url)
        assertEquals(SourceFormat.HTML, doc.format)
        assertEquals(ArticleFetcher.FALLBACK_NOTICE, doc.notice)
        assertEquals(listOf(ArticleFetcher.FALLBACK_NOTICE), warnings)
        assertTrue(doc.text.contains("almost nothing"))
        assertEquals("A tiny landing page", doc.title)
    }

    @Test
    fun fetch_followsAbsoluteAndRelativeRedirects() {
        val fetcher = fetcherFor(
            mapOf(
                "http://example.com/a" to FakeConnection(
                    301, "Moved", mapOf("Location" to "https://example.com/b"),
                ),
                "https://example.com/b" to FakeConnection(
                    302, "Found", mapOf("Location" to "/c"),
                ),
                "https://example.com/c" to FakeConnection(
                    200, "OK", mapOf("Content-Type" to "text/plain"), "redirected body".toByteArray(),
                ),
            ),
        )
        val doc = fetcher.fetch("http://example.com/a")
        assertEquals("redirected body", doc.text)
        assertEquals(SourceFormat.PLAIN, doc.format)
        assertEquals("example.com", doc.title)
    }

    @Test
    fun fetch_redirectLoop_failsAfterFiveRedirects() {
        val url = "https://example.com/loop"
        val fetcher = fetcherFor(
            mapOf(url to FakeConnection(301, "Moved", mapOf("Location" to url))),
        )
        assertEquals(
            "Couldn't open $url: too many redirects (over 5)",
            fetchFailsWith(fetcher, url),
        )
    }

    @Test
    fun fetch_pageOver5MB_fails() {
        val url = "https://example.com/huge"
        val big = ByteArray((ArticleFetcher.MAX_BYTES + 1).toInt()) { 'x'.code.toByte() }
        val fetcher = fetcherFor(
            mapOf(url to FakeConnection(200, "OK", mapOf("Content-Type" to "text/plain"), big)),
        )
        assertEquals(
            "Page is too large to read (over 5 MB): $url",
            fetchFailsWith(fetcher, url),
        )
    }

    @Test
    fun fetch_http404_reportsStatus() {
        val url = "https://example.com/missing"
        val fetcher = fetcherFor(mapOf(url to FakeConnection(404, "Not Found")))
        assertEquals(
            "Couldn't open $url: HTTP 404 Not Found",
            fetchFailsWith(fetcher, url),
        )
    }

    @Test
    fun fetch_networkError_reportsClassAndMessage() {
        val url = "https://example.com/down"
        val fetcher = ArticleFetcher({ throw IOException("boom") }, warnings::add)
        assertEquals(
            "Couldn't open $url: java.io.IOException: boom",
            fetchFailsWith(fetcher, url),
        )
    }

    @Test
    fun fetch_pdf_failsWithUnsupportedMessage() {
        val url = "https://example.com/doc.pdf"
        val fetcher = fetcherFor(
            mapOf(
                url to FakeConnection(
                    200, "OK", mapOf("Content-Type" to "application/pdf"), "%PDF-1.4".toByteArray(),
                ),
            ),
        )
        assertEquals("PDFs aren't supported yet: $url", fetchFailsWith(fetcher, url))
    }

    @Test
    fun fetch_unknownContentType_fails() {
        val url = "https://example.com/data.json"
        val fetcher = fetcherFor(
            mapOf(
                url to FakeConnection(
                    200, "OK",
                    mapOf("Content-Type" to "application/json; charset=utf-8"),
                    "{}".toByteArray(),
                ),
            ),
        )
        assertEquals(
            "Can't read this kind of page (application/json): $url",
            fetchFailsWith(fetcher, url),
        )
    }

    @Test
    fun fetch_markdown_routesByContentTypeAndByMdExtension() {
        val byType = "https://example.com/notes"
        val byExtension = "https://example.com/notes.md"
        val fetcher = fetcherFor(
            mapOf(
                byType to FakeConnection(
                    200, "OK", mapOf("Content-Type" to "text/markdown"), "# Hi".toByteArray(),
                ),
                byExtension to FakeConnection(
                    200, "OK", mapOf("Content-Type" to "text/plain"), "# Hi".toByteArray(),
                ),
            ),
        )
        assertEquals(SourceFormat.MARKDOWN, fetcher.fetch(byType).format)
        assertEquals(SourceFormat.MARKDOWN, fetcher.fetch(byExtension).format)
    }

    @Test
    fun fetch_metaCharset_decodesWindows1252() {
        val url = "https://example.com/cafe"
        val html = ("<html><head><meta charset=\"windows-1252\"></head>" +
            "<body><p>Café au lait, naïve façade, résumé.</p></body></html>")
            .toByteArray(Charsets.ISO_8859_1)
        // windows-1252 and ISO-8859-1 agree on é/ï/ç, so latin-1 bytes stand in
        // for a real windows-1252 fixture without a binary resource file.
        val fetcher = fetcherFor(mapOf(htmlPage(url, html)))
        val doc = fetcher.fetch(url)
        assertTrue("text was: ${doc.text}", doc.text.contains("Café au lait"))
    }

    @Test
    fun fetch_plainText_returnsPlainWithHostTitle() {
        val url = "https://example.com/notes.txt"
        val fetcher = fetcherFor(
            mapOf(
                url to FakeConnection(
                    200, "OK",
                    mapOf("Content-Type" to "text/plain; charset=utf-8"),
                    "Just some words.".toByteArray(),
                ),
            ),
        )
        val doc = fetcher.fetch(url)
        assertEquals(SourceFormat.PLAIN, doc.format)
        assertEquals("Just some words.", doc.text)
        assertEquals("example.com", doc.title)
        assertNull(doc.notice)
    }
}
