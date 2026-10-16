package app.readly.input

import app.readly.text.SourceFormat
import app.readly.text.TextSanitizer
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.json.JSONObject
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
    fun fetch_wikipedia_dropsReferenceSections() {
        val url = "https://en.wikipedia.org/wiki/Speech_synthesis"
        val fetcher = fetcherFor(mapOf(htmlPage(url, resource("article-wikipedia.html"))))
        val doc = fetcher.fetch(url)
        assertEquals(SourceFormat.HTML, doc.format)
        assertNull(doc.notice)
        assertTrue("title was: ${doc.title}", doc.title.contains("Speech synthesis"))

        val clean = TextSanitizer.sanitize(doc.text, SourceFormat.HTML)
        assertTrue(clean.contains("artificial production of human speech"))
        assertTrue(clean.contains("bellows and reeds"))
        assertTrue(clean.contains("sounds almost human"))
        listOf(
            // Inline citation marker and the reference list.
            "[1]", "Bruno", "Vocal Synthesis and Deep Listening", "etd.fi14040802",
            "Klatt", "Acoustical Society",
            // Lower-level heading nested inside References.
            "Printed volumes", "letterpress era",
            // See also and External links sections.
            "Concatenative synthesis", "Festival Speech Synthesis System",
            "free software toolkit",
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

    @Test
    fun fetch_title_stripsSiteSuffix() {
        fun titleFor(pageTitle: String): String {
            val url = "https://example.com/wiki"
            val html = "<html><head><title>$pageTitle</title></head>" +
                "<body><p>Almost nothing here.</p></body></html>"
            return fetcherFor(mapOf(htmlPage(url, html.toByteArray()))).fetch(url).title
        }
        assertEquals("Speech synthesis", titleFor("Speech synthesis - Wikipedia"))
        assertEquals("Article Title", titleFor("Article Title | Site Name"))
        assertEquals("Page Title", titleFor("Page Title · Example"))
        assertEquals("Hi - Blog", titleFor("Hi - Blog"))
    }

    @Test
    fun fetch_title_prefersOgTitle() {
        val url = "https://example.com/post"
        val html = "<html><head><title>Long site title - Example</title>" +
            "<meta property=\"og:title\" content=\"Real Article Title\">" +
            "</head><body><p>Almost nothing here.</p></body></html>"
        val doc = fetcherFor(mapOf(htmlPage(url, html.toByteArray()))).fetch(url)
        assertEquals("Real Article Title", doc.title)
    }

    @Test
    fun fetch_title_capsAt120CharsOnWordBoundary() {
        val url = "https://example.com/long"
        val long = "Short title " + "x".repeat(200)
        val html = "<html><head><title>$long</title></head>" +
            "<body><p>Almost nothing here.</p></body></html>"
        assertEquals(
            "Short title…",
            fetcherFor(mapOf(htmlPage(url, html.toByteArray()))).fetch(url).title,
        )
        val hard = "y".repeat(150)
        val htmlHard = "<html><head><title>$hard</title></head>" +
            "<body><p>Almost nothing here.</p></body></html>"
        assertEquals(
            "y".repeat(119) + "…",
            fetcherFor(mapOf(htmlPage(url, htmlHard.toByteArray()))).fetch(url).title,
        )
    }

    private val postId = "2111029595168538686"
    private val postNormalized = "https://x.com/UltraLinx/status/$postId"
    private val oembedUrl = "https://publish.x.com/oembed?url=" +
        "https%3A%2F%2Fx.com%2FUltraLinx%2Fstatus%2F2111029595168538686" +
        "&omit_script=1&dnt=true"

    private val oembedHtml = "<blockquote class=\"twitter-tweet\">" +
        "<p lang=\"en\" dir=\"ltr\">Now that OpenCode are giving access to DeepSeek V4.1 Flash " +
        "and GLM 5.3 Flash for FREE, how do these even compare to something like Claude or " +
        "Codex?<br><br>It seems to good to be true? Or what am I missing here?</p>" +
        "&mdash; Oliur (@UltraLinx) " +
        "<a href=\"https://x.com/UltraLinx/status/2111029595168538686\">October 16, 2026</a>" +
        "</blockquote>"

    private fun oembedJson(author: String = "Oliur", html: String = oembedHtml): ByteArray =
        JSONObject().put("author_name", author).put("html", html).toString().toByteArray()

    private fun oembedPage(body: ByteArray): Pair<String, FakeConnection> =
        oembedUrl to FakeConnection(200, "OK", mapOf("Content-Type" to "application/json"), body)

    @Test
    fun fetch_xPost_returnsOnlyPostParagraphs() {
        val fetcher = fetcherFor(mapOf(oembedPage(oembedJson())))
        val doc = fetcher.fetch("https://x.com/UltraLinx/status/$postId")
        assertEquals("Post by Oliur", doc.title)
        assertEquals(SourceFormat.HTML, doc.format)
        assertNull(doc.notice)
        assertEquals(
            "Now that OpenCode are giving access to DeepSeek V4.1 Flash and GLM 5.3 Flash " +
                "for FREE, how do these even compare to something like Claude or Codex?" +
                "\n\nIt seems to good to be true? Or what am I missing here?",
            TextSanitizer.sanitize(doc.text, SourceFormat.HTML),
        )
    }

    @Test
    fun fetch_xPost_normalizesVariantsToOneEncodedOembedUrl() {
        val requested = mutableListOf<String>()
        val opener: (String) -> FetchConnection = { url ->
            requested.add(url)
            FakeConnection(200, "OK", mapOf("Content-Type" to "application/json"), oembedJson())
        }
        val fetcher = ArticleFetcher(opener, warnings::add)
        listOf(
            "https://x.com/UltraLinx/status/$postId",
            "https://twitter.com/UltraLinx/status/$postId?s=20",
            "https://mobile.twitter.com/UltraLinx/status/$postId?t=abc",
            "https://www.twitter.com/UltraLinx/status/$postId",
            "https://www.x.com/UltraLinx/status/$postId/photo/1",
            "https://mobile.x.com/UltraLinx/status/$postId/",
        ).forEach {
            assertEquals("Post by Oliur", fetcher.fetch(it).title)
        }
        assertEquals(List(6) { oembedUrl }, requested)
    }

    @Test
    fun normalizePostUrl_acceptsOnlyStatusPaths() {
        assertEquals(
            postNormalized,
            ArticleFetcher.normalizePostUrl("https://twitter.com/UltraLinx/status/$postId?s=20"),
        )
        assertEquals(
            postNormalized,
            ArticleFetcher.normalizePostUrl("https://mobile.x.com/UltraLinx/status/$postId/photo/1"),
        )
        assertNull(ArticleFetcher.normalizePostUrl("https://x.com/UltraLinx"))
        assertNull(ArticleFetcher.normalizePostUrl("https://x.com/home"))
        assertNull(ArticleFetcher.normalizePostUrl("https://x.com/UltraLinx/status/notanid"))
        assertNull(ArticleFetcher.normalizePostUrl("https://example.com/u/status/123"))
        assertNull(ArticleFetcher.normalizePostUrl("not a url"))
    }

    @Test
    fun fetch_xPost_http404_givesPostError() {
        val fetcher = fetcherFor(
            mapOf(oembedUrl to FakeConnection(404, "Not Found")),
        )
        assertEquals(
            "Couldn't load this post from X: HTTP 404 Not Found",
            fetchFailsWith(fetcher, "https://x.com/UltraLinx/status/$postId"),
        )
    }

    @Test
    fun fetch_xPost_badJson_givesPostError() {
        val fetcher = fetcherFor(mapOf(oembedPage("not json".toByteArray())))
        val message = fetchFailsWith(fetcher, postNormalized)
        assertTrue(
            "message was: $message",
            message.startsWith("Couldn't load this post from X: ") && "JSONException" in message,
        )
    }

    @Test
    fun fetch_xPost_oembedWithoutParagraphs_givesPostError() {
        val json = JSONObject()
            .put("author_name", "Oliur")
            .put("html", "<blockquote></blockquote>")
            .toString().toByteArray()
        val fetcher = fetcherFor(mapOf(oembedPage(json)))
        assertEquals(
            "Couldn't load this post from X: oEmbed response has no post text",
            fetchFailsWith(fetcher, postNormalized),
        )
    }
}
