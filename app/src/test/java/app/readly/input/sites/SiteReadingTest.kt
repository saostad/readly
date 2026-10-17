package app.readly.input.sites

import app.readly.input.ArticleFetcher
import app.readly.input.FetchConnection
import app.readly.input.nyt.NytPolicy
import app.readly.input.sites.SiteTestFixtures.GOOGLEBOT_UA
import app.readly.input.sites.SiteTestFixtures.NYT_UA
import app.readly.input.sites.SiteTestFixtures.entry
import app.readly.input.sites.SiteTestFixtures.import
import app.readly.input.sites.SiteTestFixtures.nytEntry
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
 * End-to-end reading through the production fetcher with a pinned catalog
 * snapshot: per-hop headers, final-URL-only grouped cleanup before
 * extraction, the matched-site guard, usage tracking for honest notices,
 * and unchanged NYT/unknown/markdown behavior. All fixtures are synthetic;
 * no owner article HTML.
 */
class SiteReadingTest {

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

    private fun snapshot(vararg entries: String, legacy: NytPolicy? = null): SiteReadingSnapshot =
        SiteReadingSnapshot(import(*entries), legacy)

    private fun htmlPage(url: String, html: String): Pair<String, FakeConnection> =
        url to FakeConnection(
            200, "OK", mapOf("Content-Type" to "text/html"),
            html.toByteArray(Charsets.UTF_8),
        )

    private fun fetchFailsWith(fetcher: ArticleFetcher, url: String): String {
        try {
            fetcher.fetch(url)
        } catch (e: IOException) {
            return e.message ?: "(no message)"
        }
        fail("expected fetch of $url to fail")
        throw AssertionError("unreachable")
    }

    private val story = "The council voted six to three after a long debate on Tuesday evening. ".repeat(14)

    private fun articlePage(extra: String = ""): String =
        "<html><head><title>Story</title>" +
            "<meta property=\"og:title\" content=\"Council Votes\"></head><body>" +
            "<article><h1>Council Votes</h1>$extra<p>$story</p></article></body></html>"

    @Test
    fun matchedFetch_sendsRuleHeadersAndReadsArticle() {
        val snap = snapshot(
            entry("A", "    domain: \"a.example.com\",\n    useragent: \"googlebot\""),
        )
        val seen = LinkedHashMap<String, Map<String, String>>()
        val url = "https://a.example.com/story"
        val usage = SiteUsage()
        val opener = SiteFetchPolicy.opener(snap, usage) { requestUrl, headers ->
            seen[requestUrl] = headers
            FakeConnection(
                200, "OK", mapOf("Content-Type" to "text/html"),
                articlePage().toByteArray(Charsets.UTF_8),
            )
        }
        val doc = ArticleFetcher(opener, warnings::add, snap, usage).fetch(url)
        assertEquals(GOOGLEBOT_UA, seen[url]!!["User-Agent"])
        assertNull(doc.notice)
        assertTrue(doc.text.contains("council voted"))
        assertEquals("a.example.com", usage.finalHost)
        assertTrue(usage.applied())
        assertTrue(usage.partialApplied)
    }

    @Test
    fun cleanupInsideArticle_affectsExtractedSpeech() {
        val snap = snapshot(
            entry(
                "A",
                "    domain: \"a.example.com\",\n" +
                    "    useragent: \"googlebot\",\n" +
                    "    cs_code: [{\"hide_elem\": \"div.paywall\"}]",
            ),
        )
        val url = "https://a.example.com/story"
        val page = articlePage("<div class=\"paywall\">Subscribe now to continue reading.</div>")
        // Control: without shared fixes the paywall copy survives into speech.
        val plain = ArticleFetcher({ htmlPage(url, page).second }, warnings::add).fetch(url)
        assertTrue(
            TextSanitizer.sanitize(plain.text, SourceFormat.HTML).contains("Subscribe"),
        )
        val usage = SiteUsage()
        val doc = ArticleFetcher({ htmlPage(url, page).second }, warnings::add, snap, usage).fetch(url)
        assertNull("extracted, not fallback", doc.notice)
        val clean = TextSanitizer.sanitize(doc.text, SourceFormat.HTML)
        assertTrue(clean.contains("council voted"))
        assertFalse("paywall copy leaked into speech: $clean", clean.contains("Subscribe"))
        assertTrue(usage.cleanupApplied)
        assertTrue(usage.applied())
    }

    @Test
    fun groupedRemoval_appliesAndTracksUsage() {
        val snap = snapshot(
            entry(
                "A",
                "    domain: \"a.example.com\",\n" +
                    "    cs_code: [{\"cond\": \"div.nag\", \"rm_elem\": 1, \"rm_attrib\": \"data-x\"}]",
            ),
        )
        val url = "https://a.example.com/story"
        val page = articlePage("<div class=\"nag\" data-x=\"1\">Nag banner</div>")
        val usage = SiteUsage()
        val doc = ArticleFetcher({ htmlPage(url, page).second }, warnings::add, snap, usage).fetch(url)
        assertFalse(doc.text.contains("Nag banner"))
        assertTrue(doc.text.contains("council voted"))
        assertTrue(usage.cleanupApplied)
    }

    @Test
    fun cleanup_appliesOnlyToFinalUrlRule() {
        val snap = snapshot(
            entry(
                "A",
                "    domain: \"a.example.com\",\n" +
                    "    cs_code: [{\"hide_elem\": \"div.banner\"}]",
            ),
        )
        // Too short for Readability, so the ordinary whole-page fallback
        // applies: the banner survives unless the final page's cleanup
        // removes it.
        val bannerPage =
            "<html><head><title>Page</title></head><body>" +
                "<div class=\"banner\">Banner</div><p>A short note.</p></body></html>"
        // Matched -> unknown: the unknown final page keeps its banner.
        val responses = mapOf(
            "https://a.example.com/start" to FakeConnection(
                302, "Found", mapOf("Location" to "https://unknown.example.org/end"),
            ),
            "https://unknown.example.org/end" to FakeConnection(
                200, "OK", mapOf("Content-Type" to "text/html"),
                bannerPage.toByteArray(Charsets.UTF_8),
            ),
        )
        val usage = SiteUsage()
        val doc = ArticleFetcher(
            { url -> responses[url] ?: throw IOException("unexpected URL: $url") },
            warnings::add, snap, usage,
        ).fetch("https://a.example.com/start")
        assertTrue("unknown final page must keep its banner", doc.text.contains("Banner"))
        assertFalse(usage.cleanupApplied)
        assertEquals("unknown.example.org", usage.finalHost)

        // Unknown -> matched: the matched final page is cleaned.
        val responses2 = mapOf(
            "https://unknown.example.org/start" to FakeConnection(
                302, "Found", mapOf("Location" to "https://a.example.com/end"),
            ),
            "https://a.example.com/end" to FakeConnection(
                200, "OK", mapOf("Content-Type" to "text/html"),
                bannerPage.toByteArray(Charsets.UTF_8),
            ),
        )
        val usage2 = SiteUsage()
        val doc2 = ArticleFetcher(
            { url -> responses2[url] ?: throw IOException("unexpected URL: $url") },
            warnings::add, snap, usage2,
        ).fetch("https://unknown.example.org/start")
        assertFalse("matched final page must be cleaned", doc2.text.contains("Banner"))
        assertTrue(usage2.cleanupApplied)
    }

    @Test
    fun challengePageOnMatchedHost_failsWithActionableError() {
        val snap = snapshot(
            entry("A", "    domain: \"a.example.com\",\n    useragent: \"googlebot\""),
        )
        val url = "https://a.example.com/story"
        val page = "<html><head><title>Just a moment...</title></head>" +
            "<body><p>Verify you are human to continue.</p></body></html>"
        val message = fetchFailsWith(
            ArticleFetcher({ htmlPage(url, page).second }, warnings::add, snap, SiteUsage()),
            url,
        )
        assertTrue("message was: $message", "a.example.com" in message)
        assertTrue("message was: $message", "human-verification challenge" in message)
        assertTrue("message was: $message", "browser" in message)
    }

    @Test
    fun paywallPageOnMatchedHost_failsInsteadOfFallback() {
        val snap = snapshot(
            entry("A", "    domain: \"a.example.com\",\n    useragent: \"googlebot\""),
        )
        val url = "https://a.example.com/story"
        val page = "<html><head><title>Story</title></head><body>" +
            "<div class=\"article-paywall\"><h2>Subscribe to continue reading</h2></div></body></html>"
        val message = fetchFailsWith(
            ArticleFetcher({ htmlPage(url, page).second }, warnings::add, snap, SiteUsage()),
            url,
        )
        assertTrue("message was: $message", "subscription prompt" in message)
    }

    @Test
    fun longPaywallInsideArticle_failsInsteadOfReading() {
        val snap = snapshot(
            entry("A", "    domain: \"a.example.com\",\n    useragent: \"googlebot\""),
        )
        val url = "https://a.example.com/story"
        val pitch = "Choose a plan. Get unlimited access to all our reporting. " +
            "Payment will renew automatically. "
        val page = "<html><head><title>Subscribe</title></head><body><article>" +
            "<div class=\"paywall\"><h1>Subscribe to continue reading</h1>" +
            "<p>${pitch.repeat(9)}</p><button>Subscribe now</button></div>" +
            "</article></body></html>"
        val message = fetchFailsWith(
            ArticleFetcher({ htmlPage(url, page).second }, warnings::add, snap, SiteUsage()),
            url,
        )
        assertTrue("message was: $message", "subscription prompt" in message)
        assertTrue("message was: $message", "a.example.com" in message)
    }

    @Test
    fun longLoginFormInsideArticle_fails() {
        val snap = snapshot(
            entry("A", "    domain: \"a.example.com\",\n    useragent: \"googlebot\""),
        )
        val url = "https://a.example.com/story"
        val formText = "Enter your email address and password to sign in to your account. "
        val page = "<html><head><title>Story</title></head><body><article>" +
            "<form action=\"/login\"><h2>Sign in to continue</h2>" +
            "<p>${formText.repeat(8)}</p></form></article></body></html>"
        val message = fetchFailsWith(
            ArticleFetcher({ htmlPage(url, page).second }, warnings::add, snap, SiteUsage()),
            url,
        )
        assertTrue("message was: $message", "login" in message)
    }

    @Test
    fun longChallengeInsideArticle_fails() {
        val snap = snapshot(
            entry("A", "    domain: \"a.example.com\",\n    useragent: \"googlebot\""),
        )
        val url = "https://a.example.com/story"
        val checkText = "Verifying your browser before you can continue reading this page. "
        val page = "<html><head><title>Story</title></head><body><article>" +
            "<div class=\"cf-challenge\"><p>${checkText.repeat(8)}</p></div>" +
            "</article></body></html>"
        val message = fetchFailsWith(
            ArticleFetcher({ htmlPage(url, page).second }, warnings::add, snap, SiteUsage()),
            url,
        )
        assertTrue("message was: $message", "human-verification challenge" in message)
    }

    @Test
    fun quotedChallengePhraseInPlainDiv_readsNormally() {
        val snap = snapshot(
            entry("A", "    domain: \"a.example.com\",\n    useragent: \"googlebot\""),
        )
        val url = "https://a.example.com/story"
        val prose = "The clerk described the new verification policy at length. ".repeat(8) +
            "The notice once read “verify you are human,” the clerk recalled. " +
            "Council members asked how visitors would be affected. ".repeat(4)
        val page = "<html><head><title>Council Acts</title></head><body>" +
            "<div><h1>Council Acts</h1><p>$prose</p></div></body></html>"
        val doc = ArticleFetcher(
            { htmlPage(url, page).second },
            warnings::add, snap, SiteUsage(),
        ).fetch(url)
        assertTrue(doc.text.contains("verify you are human"))
    }

    @Test
    fun fullBodyPlusPromo_readsNormally() {
        val snap = snapshot(
            entry("A", "    domain: \"a.example.com\",\n    useragent: \"googlebot\""),
        )
        val url = "https://a.example.com/story"
        val page = articlePage(
            "<div class=\"paywall-promo\"><p>Subscribe to continue reading. Join today.</p></div>",
        )
        val doc = ArticleFetcher(
            { htmlPage(url, page).second },
            warnings::add, snap, SiteUsage(),
        ).fetch(url)
        assertNull(doc.notice)
        assertTrue(doc.text.contains("council voted"))
    }

    @Test
    fun hiddenPaywallAncestor_readsNormally() {
        val snap = snapshot(
            entry("A", "    domain: \"a.example.com\",\n    useragent: \"googlebot\""),
        )
        val url = "https://a.example.com/story"
        val page = articlePage(
            "<div hidden><div class=\"paywall\">Subscribe to continue reading. Join today.</div></div>",
        )
        val doc = ArticleFetcher(
            { htmlPage(url, page).second },
            warnings::add, snap, SiteUsage(),
        ).fetch(url)
        assertNull(doc.notice)
        assertTrue(doc.text.contains("council voted"))
    }

    @Test
    fun cleanupSelectorFailure_failsReadWithRealError() {
        // Deterministic failing-document seam: the fetcher fails the read
        // with the operation, selector, and real error — never speaks the
        // uncleaned page as if the fix had applied.
        val snap = snapshot(
            entry(
                "A",
                "    domain: \"a.example.com\",\n" +
                    "    cs_code: [{\"hide_elem\": \".banner\"}]",
            ),
        )
        val ops = SiteTestFixtures.ruleFor(snap.catalog, "a.example.com").html.cleanup
        val bad = object : org.jsoup.nodes.Document("") {
            override fun select(cssQuery: String): org.jsoup.select.Elements {
                throw IllegalStateException("selector run failed")
            }
        }
        val fetcher = ArticleFetcher({ htmlPage("", "").second }, warnings::add, snap, SiteUsage())
        try {
            fetcher.applySiteCleanup(bad, ops, "a.example.com")
            fail("expected IOException")
        } catch (e: IOException) {
            val message = e.message ?: "(no message)"
            assertTrue("message was: $message", "Couldn't clean this article from a.example.com" in message)
            assertTrue("message was: $message", ".banner" in message)
            assertTrue("message was: $message", "IllegalStateException" in message)
            assertTrue("message was: $message", "selector run failed" in message)
        }
    }

    @Test
    fun quotedPromptArticle_readsNormally() {
        val snap = snapshot(
            entry("A", "    domain: \"a.example.com\",\n    useragent: \"googlebot\""),
        )
        val url = "https://a.example.com/story"
        val quote = "The banner once read “subscribe to continue reading,” the editor recalled."
        val usage = SiteUsage()
        val opener = SiteFetchPolicy.opener(snap, usage) { _, _ ->
            htmlPage(url, articlePage("<p>$quote</p>")).second
        }
        val doc = ArticleFetcher(opener, warnings::add, snap, usage).fetch(url)
        assertNull(doc.notice)
        assertTrue(doc.text.contains("subscribe to continue reading"))
        assertTrue(usage.applied())
    }

    @Test
    fun noopRule_claimsNothing() {
        val snap = snapshot(
            entry("Idle", "    domain: \"idle.example.com\",\n    allow_cookies: 1"),
        )
        val url = "https://idle.example.com/story"
        val usage = SiteUsage()
        val doc = ArticleFetcher(
            { htmlPage(url, articlePage()).second },
            warnings::add, snap, usage,
        ).fetch(url)
        assertTrue(doc.text.contains("council voted"))
        assertFalse("no-op rule must not claim fixes", usage.applied())
        assertFalse(usage.partialApplied)
    }

    @Test
    fun unknownHost_keepsOrdinaryFallback() {
        val snap = snapshot(
            entry("A", "    domain: \"a.example.com\",\n    useragent: \"googlebot\""),
        )
        val url = "https://unknown.example.org/note"
        val page = "<html><head><title>Note</title></head><body><p>A short note.</p></body></html>"
        val usage = SiteUsage()
        val doc = ArticleFetcher(
            { htmlPage(url, page).second },
            warnings::add, snap, usage,
        ).fetch(url)
        assertEquals(ArticleFetcher.FALLBACK_NOTICE, doc.notice)
        assertTrue(doc.text.contains("short note"))
        assertFalse(usage.applied())
    }

    @Test
    fun nytBehavior_unchanged() {
        val snap = snapshot(nytEntry())
        val seen = LinkedHashMap<String, Map<String, String>>()
        val url = "https://www.nytimes.com/2024/01/01/world/story.html"
        val page = "<html><head><title>Story - The New York Times</title></head><body>" +
            "<section name=\"articleBody\"><p>$story</p></section></body></html>"
        val usage = SiteUsage()
        val opener = SiteFetchPolicy.opener(snap, usage) { requestUrl, headers ->
            seen[requestUrl] = headers
            htmlPage(url, page).second
        }
        val doc = ArticleFetcher(opener, warnings::add, snap, usage).fetch(url)
        // Exact NYT path: catalog UA sent, NYT body extractor used.
        assertEquals(NYT_UA, seen[url]!!["User-Agent"])
        assertNull(doc.notice)
        assertTrue(doc.text.contains("council voted"))
        assertTrue(usage.applied())

        // ... and the exact NYT gateway guard still fires first.
        val gate = "<html><head><title>Story</title></head><body>" +
            "<div data-testid=\"gateway-content\"><p>Subscribe to continue reading.</p></div></body></html>"
        val message = fetchFailsWith(
            ArticleFetcher({ htmlPage(url, gate).second }, warnings::add, snap, SiteUsage()),
            url,
        )
        assertTrue("message was: $message", message.startsWith("Couldn't read this New York Times article: "))
    }

    @Test
    fun legacyOverride_sendsLegacyUaForNyt() {
        val legacy = NytPolicy(
            "Legacy-UA/9.9", true, null, "9.9",
            SiteTestFixtures.SHA, NytPolicy.OFFICIAL_UPDATE_URL,
        )
        val snap = snapshot(nytEntry(), legacy = legacy)
        val seen = LinkedHashMap<String, Map<String, String>>()
        val url = "https://www.nytimes.com/2024/01/01/world/story.html"
        val usage = SiteUsage()
        val opener = SiteFetchPolicy.opener(snap, usage) { requestUrl, headers ->
            seen[requestUrl] = headers
            htmlPage(url, articlePage()).second
        }
        ArticleFetcher(opener, warnings::add, snap, usage).fetch(url)
        assertEquals("Legacy-UA/9.9", seen[url]!!["User-Agent"])
        assertTrue(usage.legacyUsed)
        assertEquals("9.9", usage.legacyVersion)
    }

    @Test
    fun markdownOnMatchedHost_unaffected() {
        val snap = snapshot(
            entry(
                "A",
                "    domain: \"a.example.com\",\n" +
                    "    cs_code: [{\"hide_elem\": \"div.banner\"}]",
            ),
        )
        val url = "https://a.example.com/notes.md"
        val usage = SiteUsage()
        val doc = ArticleFetcher(
            { FakeConnection(200, "OK", mapOf("Content-Type" to "text/markdown"), "# Notes".toByteArray()) },
            warnings::add, snap, usage,
        ).fetch(url)
        assertEquals(SourceFormat.MARKDOWN, doc.format)
        assertEquals("# Notes", doc.text)
        assertFalse(usage.cleanupApplied)
    }
}
