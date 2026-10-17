package app.readly.input.sites

import app.readly.input.ArticleFetcher
import app.readly.input.FetchConnection
import app.readly.input.nyt.NytPolicy
import app.readly.input.sites.SiteTestFixtures.GOOGLEBOT_UA
import app.readly.input.sites.SiteTestFixtures.NYT_UA
import app.readly.input.sites.SiteTestFixtures.entry
import app.readly.input.sites.SiteTestFixtures.import
import app.readly.input.sites.SiteTestFixtures.nytEntry
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-hop request routing: every redirect hop resolves headers for its own
 * URL only, from one pinned snapshot. Unknown/ineligible hops keep the
 * default headers; one host's headers never carry to another hop.
 */
class SiteFetchPolicyTest {

    private fun snapshot(vararg entries: String, legacy: NytPolicy? = null): SiteReadingSnapshot =
        SiteReadingSnapshot(import(*entries), legacy)

    private fun headers(
        url: String,
        vararg entries: String,
        legacy: NytPolicy? = null,
    ): Map<String, String> =
        SiteFetchPolicy.headersFor(url, snapshot(*entries, legacy = legacy)).headers

    @Test
    fun knownUaRefererAndExtrasResolve() {
        val h = headers(
            "https://a.example.com/story",
            entry(
                "A",
                "    domain: \"a.example.com\",\n" +
                    "    useragent: \"googlebot\",\n" +
                    "    headers_custom: {\"X-App\": \"reader\"}",
            ),
        )
        assertEquals(GOOGLEBOT_UA, h["User-Agent"])
        assertEquals("https://www.google.com/", h["Referer"])
        assertEquals("reader", h["X-App"])
    }

    @Test
    fun customUaAndCustomRefererResolve() {
        val h = headers(
            "https://www.nytimes.com/2024/01/01/world/x.html",
            nytEntry(),
        )
        assertEquals(NYT_UA, h["User-Agent"])
        assertNull(h["Referer"])

        val ref = headers(
            "https://ft.example.com/markets",
            entry("FT", "    domain: \"ft.example.com\",\n    referer: \"google\""),
        )
        assertEquals("https://www.google.com/", ref["Referer"])
        assertNull(ref["User-Agent"])
    }

    @Test
    fun unknownIneligibleAndSpoofHostsUseDefaults() {
        val entries = arrayOf(
            entry("A", "    domain: \"a.example.com\",\n    useragent: \"googlebot\""),
        )
        assertTrue(headers("https://unknown.example.org/x", *entries).isEmpty())
        assertTrue(headers("http://a.example.com/x", *entries).isEmpty())
        assertTrue(headers("https://a.example.com:8443/x", *entries).isEmpty())
        assertTrue(headers("https://user@a.example.com/x", *entries).isEmpty())
        assertTrue(headers("https://a.example.com.evil.test/x", *entries).isEmpty())
        assertTrue(headers("https://evil-a.example.com/x", *entries).isEmpty())
        assertTrue(headers("not a url", *entries).isEmpty())
        // Explicit 443 is still the default port: eligible.
        assertEquals(
            GOOGLEBOT_UA,
            headers("https://a.example.com:443/x", *entries)["User-Agent"],
        )
    }

    @Test
    fun nytCookingAndExclusionsUseDefaults() {
        val entries = arrayOf(
            nytEntry(),
            entry(
                "Ex",
                "    domain: \"ex.example.com\",\n" +
                    "    useragent: \"googlebot\",\n" +
                    "    excluded_domains: [\"private.ex.example.com\"]",
            ),
        )
        // The NYT compat scope covers the apex and www only.
        assertTrue(headers("https://cooking.nytimes.com/recipe", *entries).isEmpty())
        assertEquals(NYT_UA, headers("https://www.nytimes.com/x", *entries)["User-Agent"])
        assertTrue(headers("https://private.ex.example.com/x", *entries).isEmpty())
        assertEquals(
            GOOGLEBOT_UA,
            headers("https://public.ex.example.com/x", *entries)["User-Agent"],
        )
    }

    @Test
    fun withheldDomainsSendNoHeaders() {
        val h = headers(
            "https://child.example.com/x",
            entry(
                "Parent",
                "    domain: \"example.com\",\n" +
                    "    useragent: \"bingbot\",\n" +
                    "    exception: [{domain: \"child.example.com\", excluded_domains: \"bad\"}]",
            ),
        )
        assertTrue(h.isEmpty())
    }

    @Test
    fun noopRuleSendsNoHeaders() {
        val h = headers(
            "https://idle.example.com/x",
            entry("Idle", "    domain: \"idle.example.com\",\n    allow_cookies: 1"),
        )
        assertTrue(h.isEmpty())
    }

    @Test
    fun legacyOverride_replacesCatalogHeadersOnExactNytHosts() {
        val legacy = NytPolicy(
            "Legacy-UA/9.9", true, null, "9.9",
            SiteTestFixtures.SHA, NytPolicy.OFFICIAL_UPDATE_URL,
        )
        val entries = arrayOf(nytEntry())
        assertEquals(
            "Legacy-UA/9.9",
            headers("https://nytimes.com/x", *entries, legacy = legacy)["User-Agent"],
        )
        assertEquals(
            "Legacy-UA/9.9",
            headers("https://www.nytimes.com/x", *entries, legacy = legacy)["User-Agent"],
        )
        // ... but never on subdomains, other hosts, or ineligible URLs.
        assertTrue(headers("https://cooking.nytimes.com/x", *entries, legacy = legacy).isEmpty())
        assertTrue(headers("https://other.example.com/x", *entries, legacy = legacy).isEmpty())
        assertTrue(headers("http://nytimes.com/x", *entries, legacy = legacy).isEmpty())
    }

    // ------------------------------------------------------------------
    // Redirect hops through the production fetcher
    // ------------------------------------------------------------------

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

    @Test
    fun redirectHops_resolveHeadersPerHopWithoutCarryover() {
        val snap = snapshot(
            entry("A", "    domain: \"a.example.com\",\n    useragent: \"googlebot\""),
            entry(
                "B",
                "    domain: \"b.example.com\",\n" +
                    "    useragent_custom: \"CustomB/1.0\",\n" +
                    "    headers_custom: {\"X-B\": \"b\"}",
            ),
        )
        val seen = LinkedHashMap<String, Map<String, String>>()
        val responses = mapOf(
            "https://a.example.com/start" to FakeConnection(302, "Found", mapOf("Location" to "https://b.example.com/mid")),
            "https://b.example.com/mid" to FakeConnection(302, "Found", mapOf("Location" to "https://unknown.example.org/end")),
            "https://unknown.example.org/end" to FakeConnection(
                200, "OK", mapOf("Content-Type" to "text/plain"), "plain body".toByteArray(),
            ),
        )
        val opener = SiteFetchPolicy.opener(snap, SiteUsage()) { url, headers ->
            seen[url] = headers
            responses[url] ?: throw IOException("unexpected URL in test: $url")
        }
        val doc = ArticleFetcher(opener).fetch("https://a.example.com/start")
        assertEquals("plain body", doc.text)

        val a = seen["https://a.example.com/start"]!!
        assertEquals(GOOGLEBOT_UA, a["User-Agent"])
        assertEquals("https://www.google.com/", a["Referer"])
        assertNull("A must not carry B's headers", a["X-B"])

        val b = seen["https://b.example.com/mid"]!!
        assertEquals("CustomB/1.0", b["User-Agent"])
        assertNull("B must not carry A's Referer", b["Referer"])
        assertEquals("b", b["X-B"])

        assertTrue("unknown hop keeps defaults", seen["https://unknown.example.org/end"]!!.isEmpty())
    }

    @Test
    fun opener_tracksUsageAndIgnoresNoopRules() {
        val snap = snapshot(
            entry("A", "    domain: \"a.example.com\",\n    useragent: \"googlebot\""),
            entry("Idle", "    domain: \"idle.example.com\",\n    allow_cookies: 1"),
        )
        val usage = SiteUsage()
        val opener = SiteFetchPolicy.opener(snap, usage) { _, _ ->
            FakeConnection(200, "OK", mapOf("Content-Type" to "text/plain"), "x".toByteArray())
        }
        ArticleFetcher(opener).fetch("https://a.example.com/x")
        assertEquals(listOf("a.example.com"), usage.headerHosts.toList())
        assertTrue(usage.applied())
        assertTrue("imported googlebot rule is partial", usage.partialApplied)

        val idleUsage = SiteUsage()
        val idleOpener = SiteFetchPolicy.opener(snap, idleUsage) { _, _ ->
            FakeConnection(200, "OK", mapOf("Content-Type" to "text/plain"), "x".toByteArray())
        }
        ArticleFetcher(idleOpener).fetch("https://idle.example.com/x")
        assertTrue(idleUsage.headerHosts.isEmpty())
        assertFalse("no-op rule must not claim fixes", idleUsage.applied())
    }

    @Test
    fun usage_noticeHostPrefersFinalPage() {
        val usage = SiteUsage()
        assertEquals("this site", usage.noticeHost())
        usage.headerHosts.add("a.example.com")
        usage.finalHost = "unknown.example.org"
        assertEquals("a.example.com", usage.noticeHost())
        usage.finalHost = "a.example.com"
        assertEquals("a.example.com", usage.noticeHost())
        val cleanup = SiteUsage()
        cleanup.finalHost = "c.example.com"
        cleanup.cleanupApplied = true
        assertEquals("c.example.com", cleanup.noticeHost())
    }
}
