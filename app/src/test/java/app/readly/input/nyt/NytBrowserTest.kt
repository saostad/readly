package app.readly.input.nyt

import app.readly.input.ArticleFetcher
import app.readly.input.sites.SiteBrowserPolicy
import app.readly.text.SourceFormat
import app.readly.text.TextSanitizer
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Browser-fallback policy, DOM snapshot decoding, generation guard and
 * rendered-HTML extraction. Pure JVM: no WebView, no network, no service.
 */
class NytBrowserTest {

    private val article = "https://www.nytimes.com/2026/10/16/business/us-budget-deficit.html"

    // ------------------------------------------------------------------
    // URL gates
    // ------------------------------------------------------------------

    @Test
    fun browsing_allowsMainAndLoginSubdomains() {
        assertTrue(NytBrowserPolicy.allowsBrowsing(article))
        assertTrue(NytBrowserPolicy.allowsBrowsing("https://nytimes.com/2026/10/16/business/x.html"))
        assertTrue(NytBrowserPolicy.allowsBrowsing("https://myaccount.nytimes.com/auth/login"))
        assertTrue(NytBrowserPolicy.allowsBrowsing("https://a.b.nytimes.com/x"))
        assertTrue(NytBrowserPolicy.allowsBrowsing("https://www.nytimes.com:443/x"))
        assertTrue(NytBrowserPolicy.allowsBrowsing("HTTPS://WWW.NYTIMES.COM/x"))
    }

    @Test
    fun browsing_rejectsSpoofsAndUnsafeUrls() {
        listOf(
            "https://nytimes.com.evil.test/x",
            "https://evilnytimes.com/x",
            "https://notnytimes.com/",
            "http://www.nytimes.com/x",
            "https://user@www.nytimes.com/x",
            "https://www.nytimes.com:8443/x",
            "javascript:alert(1)",
            "about:blank",
            "file:///etc/passwd",
            "intent://x#Intent;scheme=https;end",
            "not a url",
            "",
        ).forEach { assertFalse("allowed: $it", NytBrowserPolicy.allowsBrowsing(it)) }
    }

    @Test
    fun extraction_isMainHostsOnly() {
        assertTrue(NytBrowserPolicy.allowsExtraction(article))
        assertTrue(NytBrowserPolicy.allowsExtraction("https://nytimes.com/x"))
        listOf(
            "https://myaccount.nytimes.com/auth/login",
            "https://cooking.nytimes.com/recipes/x",
            "https://nytimes.com.evil.test/x",
            "http://www.nytimes.com/x",
            "https://user@www.nytimes.com/x",
            "https://www.nytimes.com:8443/x",
        ).forEach { assertFalse("allowed: $it", NytBrowserPolicy.allowsExtraction(it)) }
    }

    @Test
    fun retryRouting_keepsExactNytLinkOnly() {
        assertEquals(article, NytBrowserPolicy.retryUrlForFailure(article))
        assertNull(NytBrowserPolicy.retryUrlForFailure("https://example.com/x"))
        assertNull(NytBrowserPolicy.retryUrlForFailure("https://myaccount.nytimes.com/auth/login"))
        assertNull(NytBrowserPolicy.retryUrlForFailure("http://www.nytimes.com/x"))
        assertNull(NytBrowserPolicy.retryUrlForFailure("https://nytimes.com.evil.test/x"))
        assertNull(NytBrowserPolicy.retryUrlForFailure("not a url"))
    }

    // ------------------------------------------------------------------
    // DOM snapshot decoding
    // ------------------------------------------------------------------

    /** Simulates the `evaluateJavascript` bridge: our JSON object arrives quoted. */
    private fun bridge(payload: JSONObject): String = JSONObject.quote(payload.toString())

    @Test
    fun decode_acceptsQuotedAndBareSnapshots() {
        val payload = JSONObject()
            .put("html", "<html><body><p>Hi.</p></body></html>")
            .put("url", article)
        val quoted = NytBrowserPolicy.decodeJsResult(bridge(payload))
        assertTrue(quoted is SiteBrowserPolicy.DomSnapshot.Ok)
        quoted as SiteBrowserPolicy.DomSnapshot.Ok
        assertEquals(article, quoted.url)
        assertTrue(quoted.html.contains("Hi."))
        val bare = NytBrowserPolicy.decodeJsResult(payload.toString())
        assertTrue(bare is SiteBrowserPolicy.DomSnapshot.Ok)
    }

    @Test
    fun decode_rejectsEmptyBridgeResults() {
        listOf(null, "", "  ", "null").forEach {
            val snap = NytBrowserPolicy.decodeJsResult(it)
            assertTrue("$it", snap is SiteBrowserPolicy.DomSnapshot.Rejected)
        }
    }

    @Test
    fun decode_rejectsPageReports() {
        val tooLarge = NytBrowserPolicy.decodeJsResult(
            bridge(JSONObject().put("tooLarge", true).put("length", 6000000).put("url", article)),
        ) as SiteBrowserPolicy.DomSnapshot.Rejected
        assertTrue(tooLarge.reason, "too large" in tooLarge.reason)
        assertTrue(tooLarge.reason, "6000000" in tooLarge.reason)

        val error = NytBrowserPolicy.decodeJsResult(
            bridge(JSONObject().put("error", "boom detail")),
        ) as SiteBrowserPolicy.DomSnapshot.Rejected
        assertTrue(error.reason, "boom detail" in error.reason)

        val empty = NytBrowserPolicy.decodeJsResult(
            bridge(JSONObject().put("empty", true).put("url", article)),
        )
        assertTrue(empty is SiteBrowserPolicy.DomSnapshot.Rejected)
    }

    @Test
    fun decode_rejectsMissingMarkupOrUrl() {
        val noHtml = NytBrowserPolicy.decodeJsResult(bridge(JSONObject().put("url", article)))
        assertTrue(noHtml is SiteBrowserPolicy.DomSnapshot.Rejected)
        val noUrl = NytBrowserPolicy.decodeJsResult(bridge(JSONObject().put("html", "<p>x</p>")))
        assertTrue(noUrl is SiteBrowserPolicy.DomSnapshot.Rejected)
        val blankHtml = NytBrowserPolicy.decodeJsResult(
            bridge(JSONObject().put("html", "  ").put("url", article)),
        )
        assertTrue(blankHtml is SiteBrowserPolicy.DomSnapshot.Rejected)
    }

    @Test
    fun decode_rejectsOversizeAndMalformed() {
        val big = "<p>" + "x".repeat(NytBrowserPolicy.MAX_DOM_BYTES) + "</p>"
        val oversize = NytBrowserPolicy.decodeJsResult(
            bridge(JSONObject().put("html", big).put("url", article)),
        ) as SiteBrowserPolicy.DomSnapshot.Rejected
        assertTrue(oversize.reason, "too large" in oversize.reason)
        // Markup itself must never leak into the reason.
        assertFalse(oversize.reason, "x".repeat(100) in oversize.reason)

        val malformed = NytBrowserPolicy.decodeJsResult("{oops")
        assertTrue(malformed is SiteBrowserPolicy.DomSnapshot.Rejected)
        val wrongType = NytBrowserPolicy.decodeJsResult("123")
        assertTrue(wrongType is SiteBrowserPolicy.DomSnapshot.Rejected)
    }

    @Test
    fun decode_enforcesByteBudget_asciiBoundary() {
        // Exactly 5 MiB ASCII (bytes == chars) is accepted.
        val exact = "x".repeat(NytBrowserPolicy.MAX_DOM_BYTES)
        val ok = NytBrowserPolicy.decodeJsResult(
            bridge(JSONObject().put("html", exact).put("url", article)),
        )
        assertTrue(ok is SiteBrowserPolicy.DomSnapshot.Ok)
        // One char over fails via the cheap char check (each char >= 1 byte).
        val over = NytBrowserPolicy.decodeJsResult(
            bridge(JSONObject().put("html", exact + "x").put("url", article)),
        ) as SiteBrowserPolicy.DomSnapshot.Rejected
        assertTrue(over.reason, "too large" in over.reason)
        assertTrue(over.reason, "5242880" in over.reason)
    }

    @Test
    fun decode_enforcesByteBudget_multibyte() {
        // 2,000,000 CJK chars = 6,000,000 UTF-8 bytes: must be rejected.
        val cjkOver = "漢".repeat(2_000_000)
        assertEquals(6_000_000, cjkOver.toByteArray(Charsets.UTF_8).size)
        val rejected = NytBrowserPolicy.decodeJsResult(
            bridge(JSONObject().put("html", cjkOver).put("url", article)),
        ) as SiteBrowserPolicy.DomSnapshot.Rejected
        assertTrue(rejected.reason, "too large" in rejected.reason)
        assertTrue(rejected.reason, "bytes" in rejected.reason)
        assertFalse(rejected.reason, "漢漢漢" in rejected.reason)
        // 1,000,000 CJK chars = 3,000,000 bytes: accepted.
        val cjkUnder = "漢".repeat(1_000_000)
        val ok = NytBrowserPolicy.decodeJsResult(
            bridge(JSONObject().put("html", cjkUnder).put("url", article)),
        )
        assertTrue(ok is SiteBrowserPolicy.DomSnapshot.Ok)
    }

    @Test
    fun decode_reportsJsTooLargeUnits() {
        val bytesReport = NytBrowserPolicy.decodeJsResult(
            bridge(
                JSONObject().put("tooLarge", true).put("length", 6_000_000)
                    .put("unit", "bytes").put("url", article),
            ),
        ) as SiteBrowserPolicy.DomSnapshot.Rejected
        assertTrue(bytesReport.reason, "6000000 bytes" in bytesReport.reason)
        val charsReport = NytBrowserPolicy.decodeJsResult(
            bridge(
                JSONObject().put("tooLarge", true).put("length", 6_000_000)
                    .put("unit", "chars").put("url", article),
            ),
        ) as SiteBrowserPolicy.DomSnapshot.Rejected
        assertTrue(charsReport.reason, "6000000 chars" in charsReport.reason)
    }

    @Test
    fun handoff_capEnforced() {
        assertNull(NytBrowserPolicy.handoffTooLargeError(NytBrowserPolicy.MAX_HANDOFF_CHARS))
        val over = NytBrowserPolicy.handoffTooLargeError(NytBrowserPolicy.MAX_HANDOFF_CHARS + 1)
        assertTrue(over!!, "131072" in over || "too large" in over)
    }

    // ------------------------------------------------------------------
    // Generation guard
    // ------------------------------------------------------------------

    @Test
    fun generation_retiresStaleReads() {
        val gen = PageGeneration()
        val first = gen.current()
        assertTrue(gen.isCurrent(first))
        gen.next()
        assertFalse(gen.isCurrent(first))
        val second = gen.current()
        assertTrue(gen.isCurrent(second))
        gen.invalidate()
        assertFalse(gen.isCurrent(second))
    }

    // ------------------------------------------------------------------
    // Rendered-HTML extraction (same guards, no refetch)
    // ------------------------------------------------------------------

    private val fetcher: ArticleFetcher
        get() = ArticleFetcher({ throw IOException("no network in this test") })

    private fun extractFailsWith(html: String, url: String): String {
        try {
            val doc = fetcher.extractRenderedHtml(html, url)
            fail("expected failure, got title=${doc.title} text=${doc.text.take(120)}")
        } catch (e: IOException) {
            return e.message ?: "(no message)"
        }
        throw AssertionError("unreachable")
    }

    @Test
    fun rendered_fullArticle_extractsBeforeHandoff() {
        val html = """
            <html><head><title>Deficit Story - The New York Times</title>
            <meta property="og:title" content="Deficit Story"></head><body>
            <nav><a href="/">Home</a></nav>
            <section name="articleBody">
            <p>Rendered paragraph one with enough words to read aloud.</p>
            <p>Rendered paragraph two with enough words to read aloud.</p>
            </section>
            <footer>Copyright</footer>
            </body></html>
        """.trimIndent()
        val doc = fetcher.extractRenderedHtml(html, article)
        assertEquals(SourceFormat.HTML, doc.format)
        assertNull(doc.notice)
        assertEquals("Deficit Story", doc.title)
        val clean = TextSanitizer.sanitize(doc.text, SourceFormat.HTML)
        assertTrue(clean, "Rendered paragraph one" in clean)
        assertTrue(clean, "Rendered paragraph two" in clean)
        assertFalse(clean, "Copyright" in clean)
    }

    @Test
    fun rendered_blockedDom_rejectedWithSameGuards() {
        val gateway = """
            <html><head><title>X - The New York Times</title></head><body>
            <section name="articleBody"><p>Teaser text here.</p></section>
            <div id="gateway-content"><h2>Subscribe to continue reading.</h2></div>
            </body></html>
        """.trimIndent()
        val gateMessage = extractFailsWith(gateway, article)
        assertTrue(gateMessage, gateMessage.startsWith(NytArticle.ERROR_PREFIX))
        assertFalse(gateMessage, "Teaser text" in gateMessage)

        val challenge = """
            <html><head><title>Just a moment...</title></head><body>
            <p>Verifying you are human.</p>
            <script src="https://ct.captcha-delivery.com/c.js"></script>
            </body></html>
        """.trimIndent()
        assertTrue(
            extractFailsWith(challenge, article),
            "human-verification challenge" in extractFailsWith(challenge, article),
        )

        val noBody = "<html><head><title>T</title></head><body><p>Hi.</p></body></html>"
        assertEquals(
            NytArticle.ERROR_PREFIX + "no readable article body was found.",
            extractFailsWith(noBody, article),
        )
    }

    @Test
    fun rendered_rejectsUnsafeUrlsAndOversize() {
        // Non-NYT website addresses now read through the ordinary guarded
        // path (see SiteBrowserTest); only unsafe targets still fail here.
        val html = "<html><head><title>T</title></head><body><p>Hi.</p></body></html>"
        for (url in listOf(
            "http://www.nytimes.com/x.html",
            "https://user@www.nytimes.com/x.html",
            "https://www.nytimes.com:8443/x.html",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "not a url",
        )) {
            val message = extractFailsWith(html, url)
            assertTrue("$url: $message", message.startsWith("Read page failed: "))
        }
        val big = extractFailsWith("x".repeat(ArticleFetcher.MAX_BYTES.toInt() + 1), article)
        assertTrue(big, "too large" in big)
    }

    @Test
    fun rendered_enforcesByteBudget_multibyte() {
        // 2,000,000 CJK chars = 6,000,000 bytes: size error, not a body error.
        val cjkOver = "漢".repeat(2_000_000)
        val over = extractFailsWith(cjkOver, article)
        assertTrue(over, "too large" in over)
        assertTrue(over, "bytes" in over)
        // Small multibyte article under budget still extracts.
        val small = """
            <html><head><title>T - The New York Times</title></head><body>
            <section name="articleBody"><p>${"漢".repeat(1000)} readable tail.</p></section>
            </body></html>
        """.trimIndent()
        val doc = fetcher.extractRenderedHtml(small, article)
        assertTrue(doc.text, "readable tail" in doc.text)
    }
}
