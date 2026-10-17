package app.readly.input.nyt

import app.readly.input.ArticleFetcher
import app.readly.input.FetchConnection
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

/**
 * NYT article extraction and incomplete-page guard, driven through
 * [ArticleFetcher] with small inline fixtures (never live pages). Non-NYT
 * fallback behavior is unchanged (see `ArticleFetcherTest`).
 */
class NytArticleTest {

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

    private val warnings = mutableListOf<String>()

    private fun fetch(url: String, html: String): ArticleFetcher.Document {
        val conn = FakeConnection(200, "OK", mapOf("Content-Type" to "text/html"), html.toByteArray())
        return ArticleFetcher({ conn }, warnings::add).fetch(url)
    }

    private fun failsWith(url: String, html: String): String {
        try {
            val doc = fetch(url, html)
            fail("expected failure, got title=${doc.title} text=${doc.text.take(120)}")
        } catch (e: IOException) {
            return e.message ?: "(no message)"
        }
        throw AssertionError("unreachable")
    }

    private val articleUrl = "https://www.nytimes.com/2026/10/16/world/riverside-park.html"

    private fun paras(n: Int, prefix: String = "Paragraph"): String =
        (1..n).joinToString("\n") { "<p>$prefix number $it with enough words to read aloud.</p>" }

    private fun fullArticle(bodyInner: String = paras(12)): String = """
        <html><head><title>Riverside Park Vote - The New York Times</title>
        <meta property="og:title" content="Riverside Park Vote">
        <script type="application/ld+json">
        {"@type":"NewsArticle","isAccessibleForFree":false,"headline":"Riverside Park Vote"}
        </script>
        </head><body>
        <nav><a href="/">Home</a><a href="/section/world">World</a></nav>
        <h1>Riverside Park Vote</h1>
        <section name="articleBody">$bodyInner</section>
        <footer>Copyright The New York Times</footer>
        </body></html>
    """.trimIndent()

    @Test
    fun fullArticle_withSubscriptionFlag_stillReads() {
        // isAccessibleForFree:false alone must not block a page with a body.
        val doc = fetch(articleUrl, fullArticle())
        assertEquals(SourceFormat.HTML, doc.format)
        assertNull(doc.notice)
        assertEquals("Riverside Park Vote", doc.title)
        val clean = TextSanitizer.sanitize(doc.text, SourceFormat.HTML)
        assertTrue(clean.contains("Paragraph number 1"))
        assertTrue(clean.contains("Paragraph number 12"))
        assertFalse("nav leaked: $clean", clean.contains("World"))
        assertFalse("footer leaked: $clean", clean.contains("Copyright"))
    }

    @Test
    fun fullArticle_dataTestidBody_reads() {
        val html = fullArticle().replace(
            "<section name=\"articleBody\">",
            "<div data-testid=\"article-body\">",
        ).replace("</section>", "</div>")
        val doc = fetch(articleUrl, html)
        assertTrue(TextSanitizer.sanitize(doc.text, SourceFormat.HTML).contains("Paragraph number 6"))
    }

    @Test
    fun fullArticle_singleArticleElement_reads() {
        val html = """
            <html><head><title>Drifted Markup - The New York Times</title></head><body>
            <article>${paras(8, "Drifted")}</article>
            </body></html>
        """.trimIndent()
        val doc = fetch(articleUrl, html)
        assertTrue(TextSanitizer.sanitize(doc.text, SourceFormat.HTML).contains("Drifted number 3"))
    }

    @Test
    fun fullArticle_chromeExcluded() {
        val body = paras(6) + """
            <aside>Related coverage: <a href="/x">another story</a></aside>
            <div class="promo">Subscribe now for unlimited recipes!</div>
            <div id="newsletter-promo">Sign up for our newsletter</div>
            <button>Share this article</button>
            <form action="/save"><input type="submit" value="Save"></form>
        """.trimIndent()
        val clean = TextSanitizer.sanitize(fetch(articleUrl, fullArticle(body)).text, SourceFormat.HTML)
        assertTrue(clean.contains("Paragraph number 6"))
        listOf("Related coverage", "another story", "Subscribe now", "newsletter", "Share this article", "Save")
            .forEach { assertFalse("chrome leaked: $it", clean.contains(it)) }
    }

    @Test
    fun teaserWithGateway_failsWithoutSpeakingTeaser() {
        val html = """
            <html><head><title>Riverside Park Vote - The New York Times</title></head><body>
            <h1>Riverside Park Vote</h1>
            <section name="articleBody">${paras(2, "Teaser")}</section>
            <div id="gateway-content"><h2>Subscribe to continue reading.</h2>
            <p>Support independent journalism.</p></div>
            </body></html>
        """.trimIndent()
        val message = failsWith(articleUrl, html)
        assertTrue("message was: $message", message.startsWith(NytArticle.ERROR_PREFIX))
        assertTrue("message was: $message", "subscription prompt" in message)
        assertFalse("teaser leaked into message: $message", "Teaser number" in message)
    }

    @Test
    fun gatewayPhraseWithoutContainer_fails() {
        val html = """
            <html><head><title>Metered Article - The New York Times</title></head><body>
            <section name="articleBody">${paras(2, "Metered")}</section>
            <div class="paywall"><p>You've reached your limit of free articles.</p></div>
            </body></html>
        """.trimIndent()
        val message = failsWith(articleUrl, html)
        assertTrue("message was: $message", "subscription prompt" in message)
    }

    @Test
    fun hiddenGatewayMarkup_doesNotBlockFullArticle() {
        val body = paras(8) +
            "<div id=\"gateway-content\" hidden><h2>Subscribe to continue reading.</h2></div>"
        val doc = fetch(articleUrl, fullArticle(body))
        assertTrue(TextSanitizer.sanitize(doc.text, SourceFormat.HTML).contains("Paragraph number 8"))
    }

    @Test
    fun accessDenied200_failsWithReason() {
        val html = """
            <html><head><title>Access Denied</title></head><body>
            <h1>Access Denied</h1>
            <p>You don't have permission to access this article. Reference #18.abc123.</p>
            </body></html>
        """.trimIndent()
        val message = failsWith(articleUrl, html)
        assertTrue("message was: $message", message.startsWith(NytArticle.ERROR_PREFIX))
        assertTrue("message was: $message", "access was denied" in message.lowercase())
    }

    @Test
    fun challengePage_failsAsUnsolvable() {
        val html = """
            <html><head><title>Just a moment...</title></head><body>
            <p>Verifying you are human. This may take a few seconds.</p>
            <script src="https://ct.captcha-delivery.com/c.js"></script>
            </body></html>
        """.trimIndent()
        val message = failsWith(articleUrl, html)
        assertTrue("message was: $message", "human-verification challenge" in message)
        assertTrue("message was: $message", "cannot solve" in message)
    }

    @Test
    fun loginPage_failsAskingForLogin() {
        val html = """
            <html><head><title>Log in - The New York Times</title></head><body>
            <h1>Log in</h1>
            <form action="/login" method="post"><input name="email"></form>
            </body></html>
        """.trimIndent()
        val message = failsWith("https://www.nytimes.com/login", html)
        assertTrue("message was: $message", "asks for a login" in message)
    }

    @Test
    fun homepage_requestsArticleLink() {
        val html = """
            <html><head><title>The New York Times</title></head><body>
            <nav><a href="/section/world">World</a><a href="/section/us">U.S.</a></nav>
            <article><h2>Top story headline</h2><p>Summary of the day.</p></article>
            <article><h2>Second headline</h2><p>Another summary.</p></article>
            </body></html>
        """.trimIndent()
        val message = failsWith("https://www.nytimes.com/", html)
        assertTrue("message was: $message", message.startsWith(NytArticle.ERROR_PREFIX))
        assertTrue("message was: $message", "homepage" in message)
        assertTrue("message was: $message", "article link" in message)
    }

    @Test
    fun missingBody_failsExactly() {
        val html = """
            <html><head><title>Something - The New York Times</title></head><body>
            <nav><a href="/">Home</a></nav>
            <div class="page"><p>A page with no article markup at all.</p></div>
            </body></html>
        """.trimIndent()
        assertEquals(
            NytArticle.ERROR_PREFIX + "no readable article body was found.",
            failsWith(articleUrl, html),
        )
    }

    @Test
    fun shortNytPage_doesNotFallBackToWholePage() {
        // The generic whole-page fallback must not speak NYT navigation.
        val html = """
            <html><head><title>A tiny page - The New York Times</title></head><body>
            <nav>World U.S. Politics Business</nav>
            <p>Almost nothing here.</p>
            </body></html>
        """.trimIndent()
        assertEquals(
            NytArticle.ERROR_PREFIX + "no readable article body was found.",
            failsWith(articleUrl, html),
        )
    }

    @Test
    fun multipleArticlesWithoutSpecificBody_failsAmbiguous() {
        val html = """
            <html><head><title>Section - The New York Times</title></head><body>
            <article><p>First card.</p></article>
            <article><p>Second card.</p></article>
            </body></html>
        """.trimIndent()
        assertEquals(
            NytArticle.ERROR_PREFIX + "no readable article body was found.",
            failsWith("https://www.nytimes.com/section/world", html),
        )
    }

    @Test
    fun articleMentioningCaptcha_stillReads() {
        // Block phrases must not false-positive on article prose when a
        // specific body exists (gateway phrases quoted in the body are
        // covered separately and also read).
        val body = """
            <p>Researchers said access was denied by the firewall. Reference manuals disagree.</p>
            <p>Please log in to comment below this story.</p>
            <p>One CAPTCHA test takes a moment to complete.</p>
        """.trimIndent() + paras(5)
        val clean = TextSanitizer.sanitize(fetch(articleUrl, fullArticle(body)).text, SourceFormat.HTML)
        assertTrue(clean.contains("firewall"))
    }

    @Test
    fun longGateHeadline_truncated() {
        val long = "Subscribe! ".repeat(40)
        val html = """
            <html><head><title>X - The New York Times</title></head><body>
            <section name="articleBody">${paras(1, "T")}</section>
            <div id="gateway-content"><h2>$long</h2></div>
            </body></html>
        """.trimIndent()
        val message = failsWith(articleUrl, html)
        assertTrue("message was: $message", "subscription prompt" in message)
        assertTrue(
            "message too long (${message.length}): $message",
            message.length <= NytArticle.ERROR_PREFIX.length + 200 + NytArticle.MAX_QUOTED_CHARS,
        )
    }

    @Test
    fun nestedListBlocks_spokenOnce() {
        val body = """
            <ul><li><p>Unique list sentence.</p></li></ul>
            <p>A following paragraph with enough words to read aloud.</p>
        """.trimIndent()
        val clean = TextSanitizer.sanitize(fetch(articleUrl, fullArticle(body)).text, SourceFormat.HTML)
        assertEquals(1, "Unique list sentence\\.".toRegex().findAll(clean).count())
        assertTrue(clean.contains("A following paragraph"))
    }

    @Test
    fun articleQuotingGatewayPhrase_stillReads() {
        // Generic prompt copy inside the article body is prose (a quote),
        // not a visible gate: it must not reject the article.
        val body = """
            <p>The phrase subscribe to continue reading appears in reports about subscription services.</p>
        """.trimIndent() + paras(5)
        val clean = TextSanitizer.sanitize(fetch(articleUrl, fullArticle(body)).text, SourceFormat.HTML)
        assertTrue("quote lost: $clean", clean.contains("subscribe to continue reading"))
        assertTrue(clean.contains("Paragraph number 5"))
    }

    @Test
    fun ancestorHiddenGateway_doesNotBlockFullArticle() {
        // The hiding markers may sit on any ancestor of the gate container.
        listOf("hidden", "aria-hidden=\"true\"", "style=\"display:none\"", "style=\"visibility: hidden\"").forEach { marker ->
            val body = paras(6) +
                "<div $marker><div id=\"gateway-content\">Subscribe to continue reading</div></div>"
            val clean = TextSanitizer.sanitize(fetch(articleUrl, fullArticle(body)).text, SourceFormat.HTML)
            assertTrue("blocked with $marker: $clean", clean.contains("Paragraph number 6"))
            assertFalse("gate leaked with $marker: $clean", clean.contains("Subscribe to continue"))
        }
    }

    @Test
    fun bareNytimesHost_alsoGuarded() {
        assertEquals(
            NytArticle.ERROR_PREFIX + "no readable article body was found.",
            failsWith(
                "https://nytimes.com/2026/10/16/world/x.html",
                "<html><head><title>T</title></head><body><p>Hi.</p></body></html>",
            ),
        )
    }
}
