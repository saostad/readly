package app.readly.input.sites

import app.readly.input.sites.SiteTestFixtures.GOOGLEBOT_UA
import app.readly.input.sites.SiteTestFixtures.entry
import app.readly.input.sites.SiteTestFixtures.import
import app.readly.input.sites.SiteTestFixtures.ruleFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Matcher, domain-boundary, scope, and request-eligibility semantics over
 * small imported catalogs.
 */
class SiteCatalogTest {
    private fun catalog(vararg entries: String): SiteCatalog = import(*entries)

    private fun requestRule(key: String, domains: Set<String>): SiteRule = SiteRule(
        siteKey = key,
        matchDomains = domains,
        excludedDomains = emptySet(),
        scope = HostScope.SUBTREE,
        request = SiteRequestPolicy(GOOGLEBOT_UA, "googlebot", "https://www.google.com/", "googlebot", emptyMap()),
        html = SiteHtmlDirectives(emptyList(), null, null),
        status = RuleStatus.FULL,
        notes = emptyList(),
    )

    @Test
    fun mostSpecificDomainWins() {
        val catalog = SiteCatalog(
            meta = testMeta(),
            rules = listOf(
                requestRule("Outer", setOf("example.com")),
                requestRule("Inner", setOf("sub.example.com")),
            ),
            skipped = emptyList(),
        )
        assertEquals("Inner", catalog.match("sub.example.com")!!.rule.siteKey)
        assertEquals("sub.example.com", catalog.match("sub.example.com")!!.matchedDomain)
        assertEquals("Inner", catalog.findRule("deep.sub.example.com")!!.siteKey)
        assertEquals("Outer", catalog.findRule("other.example.com")!!.siteKey)
        assertEquals("Outer", catalog.findRule("example.com")!!.siteKey)
    }

    @Test
    fun suffixSpoofsNeverMatch() {
        val catalog = catalog(entry("E", "    domain: \"example.com\",\n    useragent: \"googlebot\""))
        assertNull(catalog.findRule("evil-example.com"))
        assertNull(catalog.findRule("example.com.evil.com"))
        assertNull(catalog.findRule("notexample.com"))
        assertNull(catalog.findRule("exampleXcom"))
    }

    @Test
    fun ipAndMalformedHostsNeverMatch() {
        val catalog = catalog(entry("E", "    domain: \"example.com\",\n    useragent: \"googlebot\""))
        assertNull(catalog.findRule("93.184.216.34"))
        assertNull(catalog.findRule("1.2.3.4"))
        assertNull(catalog.findRule("2001:db8::1"))
        assertNull(catalog.findRule(""))
        assertNull(catalog.findRule(null))
        assertNull(catalog.findRule("exa mple.com"))
        assertNull(catalog.findRule("example.com:443"))
    }

    @Test
    fun lookupIsCaseInsensitiveAndToleratesTrailingDot() {
        val catalog = catalog(entry("E", "    domain: \"example.com\",\n    useragent: \"googlebot\""))
        assertEquals("E", catalog.findRule("WWW.EXAMPLE.COM.")!!.siteKey)
    }

    @Test
    fun apexAndWwwScopeRestrictsSubtree() {
        val catalog = import(
            entry("N", "    domain: \"nytimes.com\",\n    useragent_custom: \"UA\""),
            entry("W", "    domain: \"wide.example.com\",\n    useragent: \"googlebot\""),
        )
        assertEquals("N", catalog.findRule("nytimes.com")!!.siteKey)
        assertEquals("N", catalog.findRule("www.nytimes.com")!!.siteKey)
        assertNull(catalog.findRule("cooking.nytimes.com"))
        assertNull(catalog.findRule("deep.sub.nytimes.com"))
        assertEquals("W", catalog.findRule("deep.wide.example.com")!!.siteKey)
    }

    @Test
    fun excludedRuleFallsThroughToLessSpecific() {
        val catalog = SiteCatalog(
            meta = testMeta(),
            rules = listOf(
                requestRule("Outer", setOf("example.com")),
                requestRule("Inner", setOf("sub.example.com")).copy(
                    excludedDomains = setOf("sub.example.com"),
                ),
            ),
            skipped = emptyList(),
        )
        assertEquals("Outer", catalog.findRule("sub.example.com")!!.siteKey)
    }

    @Test
    fun unknownHostsKeepOrdinaryReading() {
        val catalog = catalog(entry("E", "    domain: \"example.com\",\n    useragent: \"googlebot\""))
        assertNull(catalog.findRule("unrelated.org"))
    }

    @Test
    fun requestEligibilityIsHttpsDefaultPortNoUserinfo() {
        assertTrue(SiteRequests.isEligibleUrl("https://example.com/article"))
        assertTrue(SiteRequests.isEligibleUrl("https://example.com:443/article"))
        assertFalse(SiteRequests.isEligibleUrl("http://example.com/article"))
        assertFalse(SiteRequests.isEligibleUrl("https://example.com:8443/article"))
        assertFalse(SiteRequests.isEligibleUrl("https://user@example.com/"))
        assertFalse(SiteRequests.isEligibleUrl("https://93.184.216.34/"))
        assertFalse(SiteRequests.isEligibleUrl("not a url"))
        assertFalse(SiteRequests.isEligibleUrl(""))
    }

    @Test
    fun resolvedHeadersCarryUaRefererAndExtras() {
        val catalog = import(
            entry(
                "H",
                "    domain: \"h.example.com\",\n" +
                    "    useragent_custom: \"Custom/1.0\",\n" +
                    "    headers_custom: {\"X-App\": \"reader\"}",
            ),
        )
        // Custom UA exists, so no referer branch; extras ride along.
        assertEquals(
            mapOf("User-Agent" to "Custom/1.0", "X-App" to "reader"),
            ruleFor(catalog, "h.example.com").request.asHeaders(),
        )
    }

    @Test
    fun compatPolicyStaysExplicitAndNarrow() {
        assertEquals(
            mapOf("nytimes.com" to HostScope.APEX_AND_WWW),
            SiteCompatPolicy.HOST_SCOPE_OVERRIDES,
        )
        assertEquals(HostScope.APEX_AND_WWW, SiteCompatPolicy.scopeFor(setOf("nytimes.com")))
        assertEquals(HostScope.SUBTREE, SiteCompatPolicy.scopeFor(setOf("example.com")))
    }

    @Test
    fun headerNameValidationRejectsNonTokens() {
        assertTrue(SiteHeaders.isValidName("X-App"))
        assertTrue(SiteHeaders.isValidName("User-Agent"))
        assertFalse(SiteHeaders.isValidName(""))
        assertFalse(SiteHeaders.isValidName("Bad Name"))
        assertFalse(SiteHeaders.isValidName("a:b"))
        assertFalse(SiteHeaders.isValidName("x".repeat(129)))
        assertFalse("Unicode letters are not RFC tokens", SiteHeaders.isValidName("X-Ĥeader"))
        assertFalse(SiteHeaders.isValidName("X-Äpfel"))
    }

    @Test
    fun withheldBarrierBlocksFallbackButNarrowerWins() {
        val catalog = SiteCatalog(
            meta = testMeta(),
            rules = listOf(
                requestRule("Outer", setOf("example.com")),
                requestRule("Deep", setOf("deep.child.example.com")),
            ),
            skipped = emptyList(),
            withheldDomains = setOf("child.example.com"),
        )
        assertNull(catalog.findRule("child.example.com"))
        assertNull(catalog.findRule("other.child.example.com"))
        assertEquals("Deep", catalog.findRule("deep.child.example.com")!!.siteKey)
        assertEquals("Deep", catalog.findRule("www.deep.child.example.com")!!.siteKey)
        assertEquals("Outer", catalog.findRule("other.example.com")!!.siteKey)
        assertEquals("Outer", catalog.findRule("example.com")!!.siteKey)
    }

    @Test
    fun selectorValidationUsesJsoupSyntax() {
        assertNull(SiteSelectors.invalidSelectorReason("div.paywall", 1024))
        assertNull(SiteSelectors.invalidSelectorReason("div.a, span#b", 1024))
        assertTrue(SiteSelectors.invalidSelectorReason("div[", 1024) != null)
        assertTrue(SiteSelectors.invalidSelectorReason("", 1024) != null)
        assertTrue(SiteSelectors.isValidAttrName("data-gated"))
        assertFalse(SiteSelectors.isValidAttrName("has space"))
        assertTrue(SiteSelectors.isValidClassName("locked"))
        assertFalse(SiteSelectors.isValidClassName("two words"))
    }

    private fun testMeta(): SiteCatalogMeta = SiteCatalogMeta(
        version = SiteTestFixtures.VERSION,
        sourceSha256 = SiteTestFixtures.SHA,
        sourceUrl = SiteCatalog.OFFICIAL_UPDATE_URL,
        attribution = SitesImporter.ATTRIBUTION,
        license = SiteTestFixtures.LICENSE,
    )
}
