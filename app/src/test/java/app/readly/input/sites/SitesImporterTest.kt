package app.readly.input.sites

import app.readly.input.sites.SiteTestFixtures.GOOGLEBOT_UA
import app.readly.input.sites.SiteTestFixtures.NYT_UA
import app.readly.input.sites.SiteTestFixtures.URL
import app.readly.input.sites.SiteTestFixtures.entry
import app.readly.input.sites.SiteTestFixtures.import
import app.readly.input.sites.SiteTestFixtures.manifestJson
import app.readly.input.sites.SiteTestFixtures.nytEntry
import app.readly.input.sites.SiteTestFixtures.ruleFor
import app.readly.input.sites.SiteTestFixtures.sitesJs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Semantic importer tests over small synthetic fixtures: grouping,
 * exceptions, withholds, skips, and taxonomy. The real archive is covered
 * separately by [SiteBundleReportTest]; no test here needs it.
 */
class SitesImporterTest {
    // ------------------------------------------------------------------
    // NYT compatibility
    // ------------------------------------------------------------------

    @Test
    fun nytImportsWithExactUaAndMainHostRestriction() {
        val catalog = import(nytEntry())
        assertEquals(1, catalog.rules.size)
        val rule = ruleFor(catalog, "nytimes.com")
        assertEquals(NYT_UA, rule.request.userAgent)
        assertEquals("custom", rule.request.userAgentSource)
        assertEquals(HostScope.APEX_AND_WWW, rule.scope)
        assertEquals(setOf("nytimes.com"), rule.matchDomains)
        assertNotNull(catalog.findRule("www.nytimes.com"))
        assertNull(catalog.findRule("cooking.nytimes.com"))
        assertEquals(RuleStatus.PARTIAL, rule.status) // browser-only allow_cookies/block_regex noted
        assertTrue(rule.notes.any { "allow_cookies" in it })
    }

    @Test
    fun newCompatibleDomainImportsWithoutCodeChange() {
        val catalog = import(
            entry("Example Gazette", "    domain: \"example-gazette.com\",\n    useragent: \"googlebot\""),
        )
        val rule = ruleFor(catalog, "www.example-gazette.com")
        assertEquals(GOOGLEBOT_UA, rule.request.userAgent)
        assertEquals("https://www.google.com/", rule.request.referer)
        assertEquals(HostScope.SUBTREE, rule.scope)
    }

    // ------------------------------------------------------------------
    // Groups, exclusions, exceptions
    // ------------------------------------------------------------------

    @Test
    fun groupExpandsAndExceptionDropsParentUa() {
        val catalog = import(
            entry(
                "Group Paper",
                "    domain: \"###_group_paper\",\n" +
                    "    group: [\"a.example.com\", \"b.example.com\"],\n" +
                    "    useragent: \"googlebot\",\n" +
                    "    exception: [{\n" +
                    "        domain: \"b.example.com\",\n" +
                    "        allow_cookies: 1\n" +
                    "      }\n" +
                    "    ]",
            ),
        )
        assertEquals(GOOGLEBOT_UA, ruleFor(catalog, "a.example.com").request.userAgent)
        val exc = ruleFor(catalog, "b.example.com")
        assertNull("exception must not inherit parent UA", exc.request.userAgent)
        assertTrue(exc.siteKey.contains("exception"))
    }

    @Test
    fun exceptionDomainArrayReplacesParentForEach() {
        val catalog = import(
            entry(
                "Array Exc",
                "    domain: \"###_array_exc\",\n" +
                    "    group: [\"one.example.com\", \"two.example.com\", \"three.example.com\"],\n" +
                    "    useragent: \"bingbot\",\n" +
                    "    exception: [{domain: [\"one.example.com\", \"two.example.com\"]}]",
            ),
        )
        assertNull(ruleFor(catalog, "one.example.com").request.userAgent)
        assertNull(ruleFor(catalog, "two.example.com").request.userAgent)
        assertTrue(ruleFor(catalog, "three.example.com").request.userAgent!!.contains("bingbot"))
    }

    @Test
    fun excludedDomainsAreHonored() {
        val catalog = import(
            entry(
                "Excl",
                "    domain: \"example.com\",\n" +
                    "    useragent: \"googlebot\",\n" +
                    "    excluded_domains: [\"internal.example.com\"]",
            ),
        )
        assertNotNull(catalog.findRule("example.com"))
        assertNull(catalog.findRule("internal.example.com"))
        assertNull(catalog.findRule("deep.internal.example.com"))
    }

    @Test
    fun invalidGroupMembersAreSkippedButSiteSurvives() {
        val catalog = import(
            entry(
                "Messy Group",
                "    domain: \"###_messy\",\n" +
                    "    group: [\"good.example.com\", \"*.bad.example.com\", \"alsobad\"],\n" +
                    "    useragent: \"googlebot\"",
            ),
        )
        assertEquals(GOOGLEBOT_UA, ruleFor(catalog, "good.example.com").request.userAgent)
        val rule = ruleFor(catalog, "good.example.com")
        assertTrue(rule.notes.any { "group member" in it })
    }

    @Test
    fun realDomainShadowedByGroup() {
        val catalog = import(
            entry(
                "Shadow",
                "    domain: \"shadow.example.com\",\n" +
                    "    group: [\"member.example.com\"],\n" +
                    "    useragent: \"googlebot\"",
            ),
        )
        assertNull("group replaces the domain list, like the extension", catalog.findRule("shadow.example.com"))
        assertNotNull(catalog.findRule("member.example.com"))
        assertTrue(ruleFor(catalog, "member.example.com").notes.any { "shadowed" in it })
    }

    @Test
    fun groupCommaStringFormIsSupported() {
        val catalog = import(
            entry(
                "Csv",
                "    domain: \"###_csv\",\n" +
                    "    group: \"a.example.com, b.example.com\",\n" +
                    "    useragent: \"googlebot\"",
            ),
        )
        assertNotNull(catalog.findRule("a.example.com"))
        assertNotNull(catalog.findRule("b.example.com"))
    }

    @Test
    fun exceptionOutsideParentGroupAppliesIndependently() {
        val catalog = import(
            entry(
                "Outside",
                "    domain: \"###_outside\",\n" +
                    "    group: [\"a.example.com\"],\n" +
                    "    useragent: \"googlebot\",\n" +
                    "    exception: [{domain: \"far.example.net\", useragent: \"facebookbot\"}]",
            ),
        )
        assertTrue(ruleFor(catalog, "far.example.net").request.userAgent!!.contains("facebook"))
        assertTrue(ruleFor(catalog, "far.example.net").notes.any { "independently" in it })
    }

    @Test
    fun unusableExceptionWithholdsItsDomainsFromParentToo() {
        val catalog = import(
            entry(
                "Nested",
                "    domain: \"###_nested\",\n" +
                    "    group: [\"a.example.com\", \"b.example.com\"],\n" +
                    "    useragent: \"googlebot\",\n" +
                    "    exception: [{domain: \"a.example.com\", group: [\"x.example.com\"]}]",
            ),
        )
        assertNull("withheld exception domain must not fall back to parent", catalog.findRule("a.example.com"))
        assertNotNull(catalog.findRule("b.example.com"))
        assertTrue(ruleFor(catalog, "b.example.com").notes.any { "withheld" in it })
    }

    // ------------------------------------------------------------------
    // Request headers
    // ------------------------------------------------------------------

    @Test
    fun headersFilterWithholdsHeadersButKeepsCleanup() {
        val catalog = import(
            entry(
                "Filtered",
                "    domain: \"filtered.example.com\",\n" +
                    "    useragent: \"googlebot\",\n" +
                    "    headers_filter: /\\/filtered\\.example\\.com\\//,\n" +
                    "    cs_code: [{\"hide_elem\": \"div.paywall\"}]",
            ),
        )
        val rule = ruleFor(catalog, "filtered.example.com")
        assertNull(rule.request.userAgent)
        assertNull(rule.request.referer)
        assertEquals(1, rule.html.cleanup.size)
        assertTrue(rule.notes.any { "headers_filter" in it })
    }

    @Test
    fun randomIpWithholdsHeaders() {
        val catalog = import(
            entry("Random", "    domain: \"random.example.com\",\n    random_ip: \"eu\""),
        )
        val rule = ruleFor(catalog, "random.example.com")
        assertTrue(!rule.request.hasEffect())
        assertTrue(rule.notes.any { "random_ip" in it })
    }

    @Test
    fun refererShadowedByUseragentFields() {
        val catalog = import(
            entry(
                "Shadow Ref",
                "    domain: \"shadowref.example.com\",\n" +
                    "    useragent: \"googlebot\",\n" +
                    "    referer_custom: \"https://example.net/\"",
            ),
        )
        val rule = ruleFor(catalog, "shadowref.example.com")
        // googlebot's own Referer stands; the custom one is shadowed away.
        assertEquals("https://www.google.com/", rule.request.referer)
        assertEquals("googlebot", rule.request.refererSource)
        assertTrue(rule.notes.any { "shadowed" in it })
    }

    @Test
    fun refererPresetAndCustomResolve() {
        val preset = import(entry("P", "    domain: \"p.example.com\",\n    referer: \"google\""))
        assertEquals("https://www.google.com/", ruleFor(preset, "p.example.com").request.referer)
        val custom = import(
            entry("C", "    domain: \"c.example.com\",\n    referer_custom: \"https://example.net/\""),
        )
        assertEquals("https://example.net/", ruleFor(custom, "c.example.com").request.referer)
        val bad = import(
            entry("B", "    domain: \"b.example.com\",\n    referer_custom: \"http://example.net/\""),
        )
        assertNull(ruleFor(bad, "b.example.com").request.referer)
    }

    @Test
    fun unknownBotAndRefererApplyNothingButNoteIt() {
        val catalog = import(
            entry(
                "Unknown",
                "    domain: \"unknown.example.com\",\n" +
                    "    useragent: \"chrome\",\n" +
                    "    referer: \"askjeeves\"",
            ),
        )
        val rule = ruleFor(catalog, "unknown.example.com")
        assertNull(rule.request.userAgent)
        assertTrue(rule.notes.any { "unknown useragent" in it })
    }

    @Test
    fun customHeadersValidatedAndForbiddenDropped() {
        val catalog = import(
            entry(
                "Headers",
                "    domain: \"headers.example.com\",\n" +
                    "    headers_custom: {\"X-App\": \"reader\", Cookie: \"x=1\", \"Bad Name\": \"v\"}",
            ),
        )
        val rule = ruleFor(catalog, "headers.example.com")
        assertEquals(mapOf("X-App" to "reader"), rule.request.extraHeaders)
        assertTrue(rule.notes.any { "Cookie" in it })
        assertTrue(rule.notes.any { "Bad Name" in it })
    }

    @Test
    fun caseCollidingCustomHeadersAreWithheld() {
        val catalog = import(
            entry(
                "Collision",
                "    domain: \"collision.example.com\",\n" +
                    "    headers_custom: {\"X-A\": \"1\", \"x-a\": \"2\"}",
            ),
        )
        assertTrue(ruleFor(catalog, "collision.example.com").request.extraHeaders.isEmpty())
    }

    @Test
    fun bingbotResolvesWithoutReferer() {
        val catalog = import(entry("Bing", "    domain: \"bing.example.com\",\n    useragent: \"bingbot\""))
        val rule = ruleFor(catalog, "bing.example.com")
        assertTrue(rule.request.userAgent!!.contains("bingbot"))
        assertNull(rule.request.referer)
    }

    // ------------------------------------------------------------------
    // Classification: pseudo, infra, browser-only, unknown
    // ------------------------------------------------------------------

    @Test
    fun pseudoSettingsAndEmptyGroupsAreSkippedNotCounted() {
        val catalog = import(
            entry("Settings", "    domain: \"###\""),
            entry("Opt-in", "    domain: \"#options_x\""),
            entry("Empty", "    domain: \"###_empty\",\n    group: []"),
            entry("Real", "    domain: \"real.example.com\",\n    useragent: \"googlebot\""),
        )
        assertEquals(1, catalog.rules.size)
        assertEquals(3, catalog.skipped.size)
        assertTrue(catalog.skipped.any { "settings marker" in it.reason })
        assertTrue(catalog.skipped.any { "empty opt-in group" in it.reason })
    }

    @Test
    fun infraEntriesNeverBecomePublisherRules() {
        val catalog = import(
            entry(
                "Vendor",
                "    domain: \"vendor.example.com\",\n" +
                    "    block_regex_general: /\\.vendor\\.example\\.com\\//,\n" +
                    "    excluded_domains: [\"vendor.example.com\"]",
            ),
            entry("Real", "    domain: \"real.example.com\",\n    useragent: \"googlebot\""),
        )
        assertNull(catalog.findRule("vendor.example.com"))
        assertEquals(1, catalog.rules.size)
        assertTrue(catalog.skipped.any { "infrastructure rule" in it.reason })
    }

    @Test
    fun browserOnlySiteImportsPartialWithNotes() {
        val catalog = import(
            entry(
                "Cookie Only",
                "    domain: \"cookie.example.com\",\n" +
                    "    allow_cookies: 1,\n" +
                    "    block_regex: /\\.tracker\\.example\\//,\n" +
                    "    cs_dompurify: 1",
            ),
        )
        val rule = ruleFor(catalog, "cookie.example.com")
        assertEquals(RuleStatus.PARTIAL, rule.status)
        assertTrue(!rule.request.hasEffect())
        assertTrue(rule.notes.any { "allow_cookies" in it })
        assertTrue(rule.notes.any { "block_regex" in it })
    }

    @Test
    fun unknownFieldMakesPartialWithoutLosingRule() {
        val catalog = import(
            entry(
                "Future",
                "    domain: \"future.example.com\",\n" +
                    "    useragent: \"googlebot\",\n" +
                    "    teleport: 1",
            ),
            entry("Neighbor", "    domain: \"neighbor.example.com\",\n    useragent: \"googlebot\""),
        )
        val rule = ruleFor(catalog, "future.example.com")
        assertEquals(GOOGLEBOT_UA, rule.request.userAgent)
        assertTrue(rule.notes.any { "unknown field" in it && "teleport" in it })
        assertNotNull(catalog.findRule("neighbor.example.com"))
    }

    @Test
    fun dynamicEntryIsSkippedButNeighborsSurvive() {
        val catalog = import(
            entry("Dynamic", "    domain: pickDomain(),\n    useragent: \"googlebot\""),
            entry("Neighbor", "    domain: \"neighbor.example.com\",\n    useragent: \"googlebot\""),
        )
        assertEquals(1, catalog.rules.size)
        assertEquals("neighbor.example.com", catalog.rules.single().matchDomains.single())
        assertTrue(catalog.skipped.any { it.siteKey == "Dynamic" })
    }

    @Test
    fun invalidDomainEntryIsSkippedWithReason() {
        val catalog = import(
            entry("Wildcard", "    domain: \"*.example.com\",\n    useragent: \"googlebot\""),
            entry("Creds", "    domain: \"user@example.com:8080\",\n    useragent: \"googlebot\""),
            entry("Suffix", "    domain: \"com\",\n    useragent: \"googlebot\""),
            entry("Real", "    domain: \"real.example.com\",\n    useragent: \"googlebot\""),
        )
        assertEquals(1, catalog.rules.size)
        assertEquals(3, catalog.skipped.size)
    }

    @Test
    fun duplicateSiteKeyKeepsFirst() {
        val catalog = import(
            entry("Dupe", "    domain: \"first.example.com\",\n    useragent: \"googlebot\""),
            entry("Dupe", "    domain: \"second.example.com\",\n    useragent: \"googlebot\""),
        )
        assertNotNull(catalog.findRule("first.example.com"))
        assertNull(catalog.findRule("second.example.com"))
        assertTrue(catalog.skipped.any { "duplicate" in it.reason })
    }

    @Test
    fun conflictingSameDomainClaimsAreWithheld() {
        val catalog = import(
            entry("A", "    domain: \"clash.example.com\",\n    useragent: \"googlebot\""),
            entry("B", "    domain: \"clash.example.com\",\n    useragent: \"facebookbot\""),
            entry("C", "    domain: \"calm.example.com\",\n    useragent: \"googlebot\""),
        )
        assertNull("ambiguous directives must match nothing", catalog.findRule("clash.example.com"))
        assertNotNull(catalog.findRule("calm.example.com"))
        assertTrue(catalog.skipped.any { "conflicting" in it.reason })
    }

    @Test
    fun identicalSameDomainClaimsMerge() {
        val catalog = import(
            entry("A", "    domain: \"same.example.com\",\n    useragent: \"googlebot\""),
            entry("B", "    domain: \"same.example.com\",\n    useragent: \"googlebot\""),
        )
        assertEquals(GOOGLEBOT_UA, ruleFor(catalog, "same.example.com").request.userAgent)
        assertTrue(catalog.rules.any { it.notes.any { n -> "merged" in n } })
    }

    // ------------------------------------------------------------------
    // Structural integrity
    // ------------------------------------------------------------------

    @Test
    fun codeInCommentsAndStringsIsNeverInterpreted() {
        val catalog = import(
            entry("Real", "    domain: \"real.example.com\",\n    useragent: \"googlebot\""),
            prefix = "// \"Fake\": { domain: \"fake.example.com\" }\n" +
                "/* \"Fake2\": { domain: \"fake2.example.com\" } */\n" +
                "var x = \"\\\"Fake3\\\": { domain: \\\"fake3.example.com\\\" }\";\n",
            suffix = "var tail = { \"Fake4\": { domain: \"fake4.example.com\" } };\n",
        )
        assertEquals(1, catalog.rules.size)
        assertNull(catalog.findRule("fake.example.com"))
        assertNull(catalog.findRule("fake2.example.com"))
        assertNull(catalog.findRule("fake3.example.com"))
        assertNull(catalog.findRule("fake4.example.com"))
    }

    @Test
    fun malformedEnclosingStructureRejectsCatalog() {
        try {
            SitesImporter.importText(
                "var x = {};\n", manifestJson(), SiteTestFixtures.LICENSE,
                SiteTestFixtures.SHA, URL,
            )
            fail("expected SiteCatalogException")
        } catch (e: SiteCatalogException) {
            assertTrue("message was: ${e.message}", "has no \"defaultSites\"" in e.message!!)
        }
        try {
            SitesImporter.importText(
                "var defaultSites = {};\nvar defaultSites = {};\n", manifestJson(),
                SiteTestFixtures.LICENSE, SiteTestFixtures.SHA, URL,
            )
            fail("expected SiteCatalogException")
        } catch (e: SiteCatalogException) {
            assertTrue("message was: ${e.message}", "multiple" in e.message!!)
        }
    }

    @Test
    fun nonObjectAndSuffixedEntriesAreSkipped() {
        val catalog = import(
            entry("Func", "    domain: \"x.example.com\"") + " + suffix",
            "  \"Plain\": \"not an object\"",
            entry("Real", "    domain: \"real.example.com\",\n    useragent: \"googlebot\""),
        )
        assertEquals(1, catalog.rules.size)
        assertEquals(2, catalog.skipped.size)
    }

    @Test
    fun duplicateFieldSkipsOneSite() {
        val catalog = import(
            entry("Dupe Field", "    domain: \"a.example.com\",\n    domain: \"b.example.com\""),
            entry("Real", "    domain: \"real.example.com\",\n    useragent: \"googlebot\""),
        )
        assertEquals(1, catalog.rules.size)
        assertTrue(catalog.skipped.any { "duplicate field" in it.reason })
    }

    @Test
    fun badProvenanceRejectsCatalog() {
        try {
            SitesImporter.importText(
                sitesJs(nytEntry()), manifestJson(), SiteTestFixtures.LICENSE, "zzz", URL,
            )
            fail("expected SiteCatalogException")
        } catch (e: SiteCatalogException) {
            assertTrue("message was: ${e.message}", "sourceSha256" in e.message!!)
        }
        try {
            SitesImporter.importText(
                sitesJs(nytEntry()), manifestJson(), SiteTestFixtures.LICENSE,
                SiteTestFixtures.SHA, "https://evil.example.com/x.zip",
            )
            fail("expected SiteCatalogException")
        } catch (e: SiteCatalogException) {
            assertTrue("message was: ${e.message}", "sourceUrl" in e.message!!)
        }
    }

    // ------------------------------------------------------------------
    // Declarative cleanup
    // ------------------------------------------------------------------

    @Test
    fun primitiveCleanupOpsParse() {
        val catalog = import(
            entry(
                "Clean",
                "    domain: \"clean.example.com\",\n" +
                    "    cs_code: [{\"hide_elem\": \"div.paywall\"}, " +
                    "{\"cond\": \"article\", \"rm_attrib\": \"data-gated|hidden\"}, " +
                    "{\"cond\": \"body\", \"rm_class\": \"locked,paywalled\"}, " +
                    "{\"cond\": \"div.nag\", \"rm_elem\": 1}]",
            ),
        )
        val ops = ruleFor(catalog, "clean.example.com").html.cleanup
        assertEquals(4, ops.size)
        assertEquals(CleanupKind.HIDE, ops[0].kind)
        assertEquals("div.paywall", ops[0].selector)
        assertEquals(CleanupKind.REMOVE_ATTRS, ops[1].kind)
        assertEquals(listOf("data-gated", "hidden"), ops[1].names)
        assertEquals(CleanupKind.REMOVE_CLASSES, ops[2].kind)
        assertEquals(listOf("locked", "paywalled"), ops[2].names)
        assertEquals(CleanupKind.REMOVE, ops[3].kind)
    }

    @Test
    fun csCodeObjectAndJsonStringFormsParse() {
        val obj = import(
            entry("Obj", "    domain: \"obj.example.com\",\n    cs_code: {\"hide_elem\": \"div.a\"}"),
        )
        assertEquals(1, ruleFor(obj, "obj.example.com").html.cleanup.size)
        val js = import(
            entry(
                "Json",
                "    domain: \"json.example.com\",\n" +
                    "    cs_code: \"{\\\"hide_elem\\\": \\\"div.b\\\"}\"",
            ),
        )
        assertEquals("div.b", ruleFor(js, "json.example.com").html.cleanup.single().selector)
    }

    @Test
    fun unknownAndBrowserOnlyCsCodeIsNotedNotApplied() {
        val catalog = import(
            entry(
                "Odd",
                "    domain: \"odd.example.com\",\n" +
                    "    cs_code: [{\"hide_elem\": \"div.ok\"}, {\"frob\": \"x\"}, " +
                    "{\"add_style\": \"div{display:none}\"}]",
            ),
        )
        val rule = ruleFor(catalog, "odd.example.com")
        assertEquals(1, rule.html.cleanup.size)
        assertTrue(rule.notes.any { "unknown key" in it })
        assertTrue(rule.notes.any { "add_style" in it })
    }

    @Test
    fun ldJsonAndAmpAreTypedButUnapplied() {
        val catalog = import(
            entry(
                "Future",
                "    domain: \"future.example.com\",\n" +
                    "    ld_json: \"div.wall|article.body\"",
            ),
            entry(
                "Amp",
                "    domain: \"amp.example.com\",\n" +
                    "    amp_redirect: \"div.wall|https://amp.example.com/{path}\"",
            ),
        )
        val ld = ruleFor(catalog, "future.example.com")
        assertEquals("div.wall", ld.html.ldJson!!.paywallSelector)
        assertEquals("article.body", ld.html.ldJson!!.articleSelector)
        assertEquals(RuleStatus.PARTIAL, ld.status)
        val amp = ruleFor(catalog, "amp.example.com")
        assertEquals("div.wall", amp.html.ampRedirect!!.paywallSelector)
        assertEquals(RuleStatus.PARTIAL, amp.status)
    }

    // ------------------------------------------------------------------
    // Round 2: withheld barriers (fail closed, no fallback reapply)
    // ------------------------------------------------------------------

    @Test
    fun unusableChildExceptionBelowApexBarsFallback() {
        val catalog = import(
            entry(
                "Parent",
                "    domain: \"example.com\",\n" +
                    "    useragent: \"bingbot\",\n" +
                    "    exception: [{domain: \"child.example.com\", excluded_domains: \"bad\"}]",
            ),
        )
        assertNull(catalog.findRule("child.example.com"))
        assertNull(catalog.findRule("deep.child.example.com"))
        assertNotNull("apex itself still matches", catalog.findRule("example.com"))
        assertNotNull(catalog.findRule("other.example.com"))
        assertTrue(catalog.withheldDomains.contains("child.example.com"))
        assertTrue(ruleFor(catalog, "example.com").notes.any { "withheld" in it })
    }

    @Test
    fun nestedExceptionBelowApexBarsSubtree() {
        val catalog = import(
            entry(
                "Parent",
                "    domain: \"example.com\",\n" +
                    "    useragent: \"bingbot\",\n" +
                    "    exception: [{domain: \"child.example.com\", group: [\"x.example.com\"]}]",
            ),
        )
        assertNull(catalog.findRule("child.example.com"))
        assertNull(catalog.findRule("deep.child.example.com"))
        assertNotNull(catalog.findRule("example.com"))
    }

    @Test
    fun overBoundExclusionsWithholdRule() {
        val excluded = (0..256).joinToString(", ") { "\"s$it.example.com\"" }
        val catalog = import(
            entry(
                "Parent",
                "    domain: \"example.com\",\n" +
                    "    useragent: \"bingbot\",\n" +
                    "    excluded_domains: [$excluded]",
            ),
            entry("Neighbor", "    domain: \"neighbor.example.com\",\n    useragent: \"googlebot\""),
        )
        assertNull("257th exclusion must not reapply parent UA", catalog.findRule("s256.example.com"))
        assertNull("over-bound rule is withheld entirely", catalog.findRule("example.com"))
        assertNotNull(catalog.findRule("neighbor.example.com"))
        assertTrue(catalog.skipped.any { it.siteKey == "Parent" && "withheld" in it.reason })
    }

    @Test
    fun exactly256ExclusionsStillImport() {
        val excluded = (0..255).joinToString(", ") { "\"s$it.example.com\"" }
        val catalog = import(
            entry(
                "Parent",
                "    domain: \"example.com\",\n" +
                    "    useragent: \"bingbot\",\n" +
                    "    excluded_domains: [$excluded]",
            ),
        )
        assertNull(catalog.findRule("s255.example.com"))
        assertNotNull(catalog.findRule("example.com"))
    }

    @Test
    fun conflictBelowApexBarsBroaderFallback() {
        val catalog = import(
            entry("Apex", "    domain: \"example.com\",\n    useragent: \"googlebot\""),
            entry("B", "    domain: \"child.example.com\",\n    useragent: \"googlebot\""),
            entry("C", "    domain: \"child.example.com\",\n    useragent: \"facebookbot\""),
        )
        assertNull(catalog.findRule("child.example.com"))
        assertNull(catalog.findRule("deep.child.example.com"))
        assertNotNull("unrelated apex subtree still matches", catalog.findRule("other.example.com"))
        assertTrue(catalog.withheldDomains.contains("child.example.com"))
    }

    @Test
    fun unknownExceptionShapeWithholdsParentDirectives() {
        val catalog = import(
            entry(
                "Parent",
                "    domain: \"example.com\",\n" +
                    "    useragent: \"bingbot\",\n" +
                    "    exception: [{allow_cookies: 1}]",
            ),
            entry("Neighbor", "    domain: \"neighbor.example.com\",\n    useragent: \"googlebot\""),
        )
        val parent = catalog.rules.first { it.siteKey == "Parent" }
        assertNull("parent UA withheld when exception scope is unknown", parent.request.userAgent)
        assertTrue(parent.notes.any { "withheld" in it })
        assertNotNull(catalog.findRule("neighbor.example.com"))
    }

    @Test
    fun nonArrayExceptionWithholdsParent() {
        val catalog = import(
            entry(
                "Parent",
                "    domain: \"example.com\",\n" +
                    "    useragent: \"bingbot\",\n" +
                    "    exception: \"oops\"",
            ),
        )
        assertNull(ruleFor(catalog, "example.com").request.userAgent)
    }

    @Test
    fun validNarrowerRuleWinsOverBarrier() {
        val catalog = import(
            entry(
                "Parent",
                "    domain: \"example.com\",\n" +
                    "    useragent: \"bingbot\",\n" +
                    "    exception: [{domain: \"child.example.com\", excluded_domains: \"bad\"}]",
            ),
            entry("Deep", "    domain: \"deep.child.example.com\",\n    useragent: \"googlebot\""),
        )
        assertEquals(GOOGLEBOT_UA, ruleFor(catalog, "deep.child.example.com").request.userAgent)
        assertNull(catalog.findRule("child.example.com"))
        assertNull(catalog.findRule("other.child.example.com"))
    }

    // ------------------------------------------------------------------
    // Round 2: strict root grammar
    // ------------------------------------------------------------------

    private fun expectCatalogReject(sitesJs: String, fragment: String) {
        try {
            SitesImporter.importText(
                sitesJs, manifestJson(), SiteTestFixtures.LICENSE, SiteTestFixtures.SHA, URL,
            )
            fail("expected SiteCatalogException containing $fragment")
        } catch (e: SiteCatalogException) {
            assertTrue("message was: ${e.message}", fragment in e.message!!)
        }
    }

    @Test
    fun missingCommaBetweenEntriesRejectsCatalog() {
        val a = entry("A", "    domain: \"a.example.com\",\n    useragent: \"bingbot\"")
        val b = entry("B", "    domain: \"b.example.com\",\n    useragent: \"googlebot\"")
        expectCatalogReject("var defaultSites = {\n$a\n$b\n};\n", "missing ','")
    }

    @Test
    fun missingColonRejectsCatalog() {
        expectCatalogReject(
            "var defaultSites = {\n  \"A\" {domain: \"a.example.com\"}\n};\n",
            "missing ':'",
        )
    }

    @Test
    fun bareJunkBetweenEntriesRejectsCatalog() {
        val a = entry("A", "    domain: \"a.example.com\",\n    useragent: \"bingbot\"")
        val b = entry("B", "    domain: \"b.example.com\",\n    useragent: \"googlebot\"")
        expectCatalogReject("var defaultSites = {\n$a,\n  junk garbage,\n$b\n};\n", "missing ':'")
    }

    @Test
    fun crossedDelimitersRejectCatalog() {
        expectCatalogReject(
            "var defaultSites = {\n  \"A\": {domain: [\"a.example.com\"}]\n};\n",
            "crossed brackets",
        )
    }

    @Test
    fun unicodeHeaderNameRejectedButSiblingKept() {
        val catalog = import(
            entry(
                "Headers",
                "    domain: \"headers.example.com\",\n" +
                    "    headers_custom: {\"X-App\": \"reader\", \"X-Ĥeader\": \"v\"}",
            ),
        )
        val rule = ruleFor(catalog, "headers.example.com")
        assertEquals(mapOf("X-App" to "reader"), rule.request.extraHeaders)
        assertTrue(rule.notes.any { "invalid name" in it })
    }

    // ------------------------------------------------------------------
    // Round 2: multi-action cleanup
    // ------------------------------------------------------------------

    @Test
    fun combinedClassAndAttributeRemovalPreservesBoth() {
        val catalog = import(
            entry(
                "Clean",
                "    domain: \"clean.example.com\",\n" +
                    "    cs_code: [{\"cond\": \".article\", \"rm_class\": \"locked\", \"rm_attrib\": \"hidden\"}]",
            ),
        )
        val ops = ruleFor(catalog, "clean.example.com").html.cleanup
        assertEquals(2, ops.size)
        assertEquals(CleanupKind.REMOVE_CLASSES, ops[0].kind)
        assertEquals(listOf("locked"), ops[0].names)
        assertEquals(CleanupKind.REMOVE_ATTRS, ops[1].kind)
        assertEquals(listOf("hidden"), ops[1].names)
    }

    @Test
    fun removalPlusAttributesPreservesBoth() {
        val catalog = import(
            entry(
                "Clean",
                "    domain: \"clean.example.com\",\n" +
                    "    cs_code: [{\"cond\": \"div.nag\", \"rm_elem\": 1, \"rm_attrib\": \"data-x\"}]",
            ),
        )
        val ops = ruleFor(catalog, "clean.example.com").html.cleanup
        assertEquals(2, ops.size)
        assertEquals(CleanupKind.REMOVE, ops[0].kind)
        assertEquals(CleanupKind.REMOVE_ATTRS, ops[1].kind)
    }

    @Test
    fun nestedElemsAttachOnceToFirstSibling() {
        val catalog = import(
            entry(
                "Clean",
                "    domain: \"clean.example.com\",\n" +
                    "    cs_code: [{" +
                    "\"cond\": \".outer\", \"rm_class\": \"a\", \"rm_attrib\": \"b\", " +
                    "\"elems\": [{\"cond\": \".inner\", \"rm_elem\": 1}]}]",
            ),
        )
        val ops = ruleFor(catalog, "clean.example.com").html.cleanup
        assertEquals(2, ops.size)
        assertEquals(1, ops[0].children.size)
        assertEquals(".inner", ops[0].children.single().selector)
        assertTrue(ops[1].children.isEmpty())
    }

    @Test
    fun supportedPlusUnknownActionNotesUnknown() {
        val catalog = import(
            entry(
                "Clean",
                "    domain: \"clean.example.com\",\n" +
                    "    cs_code: [{\"cond\": \".a\", \"rm_class\": \"x\", \"set_attrib\": \"a|b\"}, " +
                    "{\"cond\": \".b\", \"rm_elem\": 1, \"frob\": 1}]",
            ),
        )
        val rule = ruleFor(catalog, "clean.example.com")
        assertEquals(2, rule.html.cleanup.size)
        assertTrue(rule.notes.any { "set_attrib" in it })
        assertTrue(rule.notes.any { "unknown key" in it && "frob" in it })
    }

    // ------------------------------------------------------------------
    // Round 2: JSON-string arrays and parity
    // ------------------------------------------------------------------

    @Test
    fun csCodeJsonArrayStringParses() {
        val catalog = import(
            entry(
                "JsonArr",
                "    domain: \"jsonarr.example.com\",\n" +
                    "    cs_code: '[{\"hide_elem\": \".banner\"}, {\"cond\": \".a\", \"rm_elem\": 1}]'",
            ),
        )
        val ops = ruleFor(catalog, "jsonarr.example.com").html.cleanup
        assertEquals(2, ops.size)
        assertEquals(".banner", ops[0].selector)
        assertEquals(CleanupKind.REMOVE, ops[1].kind)
    }

    @Test
    fun csCodeJsonStringWithTrailingTokensSkipped() {
        val catalog = import(
            entry(
                "JsonBad",
                "    domain: \"jsonbad.example.com\",\n" +
                    "    cs_code: '{\"hide_elem\": \".a\"} garbage'",
            ),
        )
        assertTrue(ruleFor(catalog, "jsonbad.example.com").html.cleanup.isEmpty())
    }

    @Test
    fun ldJsonEmptyAndOversizedExtrasSkipped() {
        val long = "e".repeat(SiteCatalogLimits.MAX_LD_JSON_EXTRA_LENGTH + 1)
        val catalog = import(
            entry("E1", "    domain: \"e1.example.com\",\n    ld_json: \"div.w|article|\""),
            entry("E2", "    domain: \"e2.example.com\",\n    ld_json: \"div.w|article|$long\""),
            entry(
                "E3",
                "    domain: \"e3.example.com\",\n" +
                    "    ld_json: \"div.w|article|ok1||ok3|ok4|ok5|ok6\"",
            ),
        )
        assertNull("empty extra must not round-trip", ruleFor(catalog, "e1.example.com").html.ldJson)
        assertNull(ruleFor(catalog, "e2.example.com").html.ldJson)
        assertNull("truncated extras are still validated", ruleFor(catalog, "e3.example.com").html.ldJson)
    }

    @Test
    fun newlineSiteKeySkippedButNeighborSurvives() {
        val catalog = import(
            entry("A\\nB", "    domain: \"a.example.com\",\n    useragent: \"googlebot\""),
            entry("Real", "    domain: \"real.example.com\",\n    useragent: \"googlebot\""),
        )
        assertEquals(1, catalog.rules.size)
        assertNotNull(catalog.findRule("real.example.com"))
        assertTrue(catalog.skipped.any { "newline" in it.reason })
        // The skipped record itself must stay codec-valid (single line).
        SiteCatalogJson.decode(SiteCatalogJson.encode(catalog))
    }

    @Test
    fun missingLicenseRejectsCatalog() {
        try {
            SitesImporter.importText(
                sitesJs(entry("A", "    domain: \"a.example.com\"")),
                manifestJson(), null, SiteTestFixtures.SHA, URL,
            )
            fail("expected SiteCatalogException")
        } catch (e: SiteCatalogException) {
            assertTrue("message was: ${e.message}", "LICENSE" in e.message!!)
        }
    }
}
