package app.readly.input.sites

import app.readly.input.sites.SiteTestFixtures.entry
import app.readly.input.sites.SiteTestFixtures.import
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Restriction bounds fail closed: barrier overflow rejects the whole
 * candidate catalog (never a truncated catalog that leaks parent
 * directives), and withheld rules bar their match domains against broader
 * fallback. Covers the BarrierBoundProbe scenario (5000 unusable child
 * exceptions in ~270 KiB) plus the conflict, exclusion, trim, and
 * catalog-full paths.
 */
class SiteBarrierTest {
    private fun parentWithUnusableExceptions(id: Int, children: IntRange): String {
        val exceptions = children.joinToString(", ") { j ->
            "{domain: \"s$j.p$id.example.com\", excluded_domains: \"bad\"}"
        }
        return entry(
            "P$id",
            "    domain: \"p$id.example.com\",\n" +
                "    useragent: \"bingbot\",\n" +
                "    exception: [$exceptions]",
        )
    }

    @Test
    fun barrierOverflowRejectsCatalog() {
        // 5 parents x 1000 unusable children = 5000 barriers: the catalog is
        // rejected, not truncated to 4096 with the rest leaking parent UA.
        val entries = (0..4).map { i -> parentWithUnusableExceptions(i, 0..999) }.toTypedArray()
        try {
            import(*entries)
            fail("expected SiteCatalogOverflowException")
        } catch (e: SiteCatalogOverflowException) {
            assertTrue("message was: ${e.message}", "4096" in e.message!!)
            assertTrue("message was: ${e.message}", "truncated" in e.message!!)
        }
    }

    @Test
    fun barrierBoundIsExact() {
        // Exactly 4096 barriers import fine; one more rejects.
        val ok = import(
            *(0..3).map { i -> parentWithUnusableExceptions(i, 0..1023) }.toTypedArray(),
        )
        assertEquals(SiteCatalogLimits.MAX_WITHHELD_DOMAINS, ok.withheldDomains.size)
        assertNull(ok.findRule("s1023.p3.example.com"))
        assertNotNull(ok.findRule("p0.example.com"))

        val plusOne = (0..3).map { i ->
            parentWithUnusableExceptions(i, if (i == 0) 0..1024 else 0..1023)
        }.toTypedArray()
        try {
            import(*plusOne)
            fail("expected SiteCatalogOverflowException")
        } catch (e: SiteCatalogOverflowException) {
            assertTrue("message was: ${e.message}", "4096" in e.message!!)
        }
    }

    @Test
    fun conflictOverflowRejectsCatalog() {
        // 4096 exception barriers plus one conflicting domain: the conflict
        // path must throw too, not silently drop the barrier.
        val entries = (0..3).map { i -> parentWithUnusableExceptions(i, 0..1023) } +
            entry("ClashA", "    domain: \"clash.example.com\",\n    useragent: \"googlebot\"") +
            entry("ClashB", "    domain: \"clash.example.com\",\n    useragent: \"facebookbot\"")
        try {
            import(*entries.toTypedArray())
            fail("expected SiteCatalogOverflowException")
        } catch (e: SiteCatalogOverflowException) {
            assertTrue("message was: ${e.message}", "4096" in e.message!!)
        }
    }

    @Test
    fun overBoundExcludedRuleBarsItsDomainsBelowBroaderRule() {
        val excluded = (0..256).joinToString(", ") { "\"s$it.example.com\"" }
        val catalog = import(
            entry("Broad", "    domain: \"example.com\",\n    useragent: \"bingbot\""),
            entry(
                "Child",
                "    domain: \"child.example.com\",\n" +
                    "    useragent: \"googlebot\",\n" +
                    "    excluded_domains: [$excluded]",
            ),
        )
        // The child is withheld AND barred: no Bingbot fallback from Broad.
        assertNull(catalog.findRule("child.example.com"))
        assertNull(catalog.findRule("deep.child.example.com"))
        assertTrue(catalog.withheldDomains.contains("child.example.com"))
        assertEquals(
            "bingbot",
            catalog.findRule("other.example.com")!!.request.userAgentSource,
        )
        assertTrue(
            catalog.skipped.any { it.siteKey == "Child" && "barred" in it.reason },
        )
    }

    @Test
    fun invalidExcludedShapeBarsItsDomainsBelowBroaderRule() {
        val catalog = import(
            entry("Broad", "    domain: \"example.com\",\n    useragent: \"bingbot\""),
            entry(
                "Child",
                "    domain: \"child.example.com\",\n" +
                    "    useragent: \"googlebot\",\n" +
                    "    excluded_domains: \"bad\"",
            ),
        )
        assertNull(catalog.findRule("child.example.com"))
        assertTrue(catalog.withheldDomains.contains("child.example.com"))
        assertEquals(
            "bingbot",
            catalog.findRule("other.example.com")!!.request.userAgentSource,
        )
    }

    @Test
    fun trimmedMatchDomainsAreBarredBelowBroaderRule() {
        val members = (0..512).joinToString(", ") { "\"s$it.example.com\"" }
        val catalog = import(
            entry("Broad", "    domain: \"example.com\",\n    useragent: \"bingbot\""),
            entry(
                "Group",
                "    domain: \"###_g\",\n" +
                    "    group: [$members],\n" +
                    "    useragent: \"googlebot\"",
            ),
        )
        assertEquals(
            "googlebot",
            catalog.findRule("s0.example.com")!!.request.userAgentSource,
        )
        // s512 was trimmed off the 512-domain rule: barred, not Bingbot.
        assertNull(catalog.findRule("s512.example.com"))
        assertTrue(catalog.withheldDomains.contains("s512.example.com"))
        assertTrue(
            ruleForKept(catalog).notes.any { "barred" in it },
        )
    }

    private fun ruleForKept(catalog: SiteCatalog): SiteRule =
        catalog.findRule("s0.example.com") ?: throw AssertionError("kept member missing")

    @Test(timeout = 60_000)
    fun catalogFullDropBarsDroppedDomains() {
        val entries = mutableListOf(
            entry("Broad", "    domain: \"example.com\",\n    useragent: \"bingbot\""),
        )
        for (i in 0..4096) {
            entries.add(entry("R$i", "    domain: \"r$i.example.com\",\n    useragent: \"googlebot\""))
        }
        val catalog = import(*entries.toTypedArray())
        assertEquals(SiteCatalogLimits.MAX_RULES, catalog.rules.size)
        // R4096 was dropped by the rule cap: barred, so Broad's Bingbot must
        // not leak onto it.
        assertNull(catalog.findRule("r4096.example.com"))
        assertTrue(catalog.withheldDomains.contains("r4096.example.com"))
        assertEquals(
            "googlebot",
            catalog.findRule("r0.example.com")!!.request.userAgentSource,
        )
        assertTrue(catalog.skipped.any { it.siteKey == "(catalog bound)" && "barred" in it.reason })
    }
}
