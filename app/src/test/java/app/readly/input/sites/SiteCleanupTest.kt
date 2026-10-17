package app.readly.input.sites

import app.readly.input.sites.SiteTestFixtures.entry
import app.readly.input.sites.SiteTestFixtures.import
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.jsoup.Jsoup

/**
 * Executable cleanup groups: one upstream `cond` keeps one matched-node set
 * for all its sibling actions (no re-selection after mutation), and nested
 * `elems` run globally once when the parent matched. Covers importer
 * grouping, codec persistence/validation, and runtime DOM behavior.
 */
class SiteCleanupTest {
    // ------------------------------------------------------------------
    // Importer grouping
    // ------------------------------------------------------------------

    @Test
    fun siblingsShareOneGroupAndUnrelatedSelectorsDiffer() {
        val catalog = import(
            entry(
                "Clean",
                "    domain: \"clean.example.com\",\n" +
                    "    cs_code: [" +
                    "{\"cond\": \".locked\", \"rm_class\": \"locked\", \"rm_attrib\": \"hidden\"}, " +
                    "{\"cond\": \".other\", \"rm_elem\": 1}, " +
                    "{\"hide_elem\": \".banner\"}]",
            ),
        )
        val ops = SiteTestFixtures.ruleFor(catalog, "clean.example.com").html.cleanup
        assertEquals(4, ops.size)
        assertEquals("siblings must share one group", ops[0].groupId, ops[1].groupId)
        assertNotEquals(ops[0].groupId, ops[2].groupId)
        assertNotEquals(ops[0].groupId, ops[3].groupId)
        assertNotEquals(ops[2].groupId, ops[3].groupId)
    }

    @Test
    fun nestedChildrenGetTheirOwnGroups() {
        val catalog = import(
            entry(
                "Clean",
                "    domain: \"clean.example.com\",\n" +
                    "    cs_code: [{" +
                    "\"cond\": \".outer\", \"rm_class\": \"a\", \"rm_attrib\": \"b\", " +
                    "\"elems\": [{\"cond\": \".in\", \"rm_class\": \"x\", \"rm_attrib\": \"y\"}]}]",
            ),
        )
        val ops = SiteTestFixtures.ruleFor(catalog, "clean.example.com").html.cleanup
        assertEquals(ops[0].groupId, ops[1].groupId)
        val children = ops[0].children
        assertEquals(2, children.size)
        assertEquals("nested siblings share one group", children[0].groupId, children[1].groupId)
        assertNotEquals("nested group differs from parent", ops[0].groupId, children[0].groupId)
    }

    // ------------------------------------------------------------------
    // Codec grouping
    // ------------------------------------------------------------------

    @Test
    fun groupIdsRoundTrip() {
        val catalog = import(
            entry(
                "Clean",
                "    domain: \"clean.example.com\",\n" +
                    "    cs_code: [{" +
                    "\"cond\": \".locked\", \"rm_class\": \"locked\", \"rm_attrib\": \"hidden\", " +
                    "\"elems\": [{\"hide_elem\": \".banner\"}]}]",
            ),
        )
        val decoded = SiteCatalogJson.decode(SiteCatalogJson.encode(catalog))
        assertEquals(catalog, decoded)
    }

    private fun tamperedCleanup(catalog: SiteCatalog, mutate: (JSONObject) -> Unit): String {
        val json = JSONObject(SiteCatalogJson.encode(catalog))
        mutate(
            json.getJSONArray("rules").getJSONObject(0)
                .getJSONObject("html").getJSONArray("cleanup").getJSONObject(0),
        )
        return json.toString()
    }

    private fun expectReject(json: String, fragment: String) {
        try {
            SiteCatalogJson.decode(json)
            fail("expected SiteCatalogException containing $fragment")
        } catch (e: SiteCatalogException) {
            assertTrue("message was: ${e.message}", fragment in e.message!!)
        }
    }

    @Test
    fun invalidGroupIdsRejected() {
        val catalog = import(
            entry("C", "    domain: \"c.example.com\",\n    cs_code: [{\"hide_elem\": \"div.ok\"}]"),
        )
        expectReject(tamperedCleanup(catalog) { it.put("groupId", -1) }, "groupId")
        expectReject(tamperedCleanup(catalog) { it.put("groupId", "zero") }, "groupId")
        val missing = JSONObject(SiteCatalogJson.encode(catalog))
        missing.getJSONArray("rules").getJSONObject(0)
            .getJSONObject("html").getJSONArray("cleanup").getJSONObject(0).remove("groupId")
        expectReject(missing.toString(), "groupId")
    }

    @Test
    fun mergedGroupSelectorsRejected() {
        val catalog = import(
            entry(
                "C",
                "    domain: \"c.example.com\",\n" +
                    "    cs_code: [{\"hide_elem\": \".a\"}, {\"hide_elem\": \".b\"}]",
            ),
        )
        val json = JSONObject(SiteCatalogJson.encode(catalog))
        val ops = json.getJSONArray("rules").getJSONObject(0)
            .getJSONObject("html").getJSONArray("cleanup")
        // Tamper: force both ops into one group with different selectors.
        ops.getJSONObject(1).put("groupId", ops.getJSONObject(0).getInt("groupId"))
        expectReject(json.toString(), "groupId")
    }

    @Test
    fun schema1WithoutGroupsRejected() {
        val catalog = import(
            entry("C", "    domain: \"c.example.com\",\n    cs_code: [{\"hide_elem\": \"div.ok\"}]"),
        )
        val json = JSONObject(SiteCatalogJson.encode(catalog))
        json.put("schema", 1)
        try {
            SiteCatalogJson.decode(json.toString())
            fail("expected SiteCatalogException")
        } catch (e: SiteCatalogException) {
            assertTrue("message was: ${e.message}", "unsupported schema" in e.message!!)
        }
    }

    // ------------------------------------------------------------------
    // Runtime DOM behavior
    // ------------------------------------------------------------------

    private fun opsFor(csCode: String): List<CleanupOp> {
        val catalog = import(
            entry("Clean", "    domain: \"clean.example.com\",\n    cs_code: $csCode"),
        )
        return SiteTestFixtures.ruleFor(catalog, "clean.example.com").html.cleanup
    }

    @Test
    fun siblingActionsShareTheOriginalMatchedNodes() {
        // The motivating bug: re-selecting ".locked" after removing the class
        // would miss the node and leave `hidden` behind.
        val ops = opsFor("[{\"cond\": \".locked\", \"rm_class\": \"locked\", \"rm_attrib\": \"hidden\"}]")
        assertEquals(2, ops.size)
        val doc = Jsoup.parse(
            "<html><body><article class=\"locked\" hidden><p>Hello</p></article></body></html>",
        )
        val report = SiteCleanup.apply(doc, ops)
        assertTrue(report.applied())
        val article = doc.selectFirst("article")!!
        assertTrue("class must be gone", !article.hasClass("locked"))
        assertTrue("attribute must be gone", !article.hasAttr("hidden"))
        assertEquals("Hello", article.text())
    }

    @Test
    fun multipleParentsRunNestedOnce() {
        val ops = opsFor(
            "[{\"cond\": \".post\", \"rm_class\": \"gated\", " +
                "\"elems\": [{\"hide_elem\": \".banner\"}]}]",
        )
        val doc = Jsoup.parse(
            "<html><body>" +
                "<div class=\"post gated\"><p>One</p></div>" +
                "<div class=\"post gated\"><p>Two</p></div>" +
                "<div class=\"post gated\"><p>Three</p></div>" +
                "<div class=\"banner\">Ad</div><div class=\"banner\">Ad2</div>" +
                "</body></html>",
        )
        val report = SiteCleanup.apply(doc, ops)
        // Parent group + nested group, each counted once despite 3 parents.
        assertEquals(2, report.groupsMatched)
        assertTrue(doc.select(".banner").isEmpty())
        assertTrue(doc.select(".gated").isEmpty())
        assertEquals(3, doc.select(".post").size)
    }

    @Test
    fun zeroParentsSkipNested() {
        val ops = opsFor(
            "[{\"cond\": \".missing\", \"rm_class\": \"gated\", " +
                "\"elems\": [{\"hide_elem\": \".banner\"}]}]",
        )
        val doc = Jsoup.parse(
            "<html><body><div class=\"banner\">Keep</div><p>Text</p></body></html>",
        )
        val report = SiteCleanup.apply(doc, ops)
        assertTrue(!report.applied())
        assertEquals("banner must survive when no parent matched", 1, doc.select(".banner").size)
    }

    @Test
    fun removalStillTriggersNested() {
        val ops = opsFor(
            "[{\"cond\": \".nag\", \"rm_elem\": 1, \"elems\": [{\"hide_elem\": \".banner\"}]}]",
        )
        val doc = Jsoup.parse(
            "<html><body><div class=\"nag\">Nag</div><div class=\"banner\">Ad</div><p>Text</p></body></html>",
        )
        val report = SiteCleanup.apply(doc, ops)
        assertTrue(report.applied())
        assertTrue(doc.select(".nag").isEmpty())
        assertTrue("nested must run even though the parent was removed", doc.select(".banner").isEmpty())
    }

    @Test
    fun selectorFailure_recordedWithOperationAndError() {
        // A selector Jsoup cannot run is never silent: the report names the
        // group, the selector, and the real error instead of looking applied.
        val ops = opsFor("[{\"hide_elem\": \".banner\"}]")
        val bad = object : org.jsoup.nodes.Document("") {
            override fun select(cssQuery: String): org.jsoup.select.Elements {
                throw IllegalStateException("selector run failed")
            }
        }
        val report = SiteCleanup.apply(bad, ops)
        assertTrue(report.hasErrors())
        assertEquals(0, report.groupsMatched)
        assertEquals(1, report.errors.size)
        val failure = report.errors.single()
        assertEquals(ops.single().groupId, failure.groupId)
        assertEquals(".banner", failure.selector)
        assertEquals("java.lang.IllegalStateException", failure.errorClass)
        assertEquals("selector run failed", failure.message)
    }

    @Test
    fun selectorFailure_runsRemainingGroups() {
        // One bad group must not hide the rest: the good sibling still
        // applies, and its match is still reported alongside the error.
        val ops = opsFor("[{\"hide_elem\": \".bad\"}, {\"hide_elem\": \"div.banner\"}]")
        val doc = object : org.jsoup.nodes.Document("") {
            override fun select(cssQuery: String): org.jsoup.select.Elements {
                if (cssQuery == ".bad") throw IllegalStateException("selector run failed")
                return super.select(cssQuery)
            }
        }
        doc.body().append("<div class=\"banner\">Ad</div><p>Text</p>")
        val report = SiteCleanup.apply(doc, ops)
        assertTrue(report.hasErrors())
        assertEquals(".bad", report.errors.single().selector)
        assertEquals(1, report.groupsMatched)
        assertEquals(1, report.nodesRemoved)
        assertTrue(doc.select("div.banner").isEmpty())
    }

    @Test
    fun hideRemovesNodesFromSpokenDom() {
        val ops = opsFor("[{\"hide_elem\": \"div.paywall\"}]")
        val doc = Jsoup.parse(
            "<html><body><div class=\"paywall\">Subscribe!</div><article><p>Story</p></article></body></html>",
        )
        val report = SiteCleanup.apply(doc, ops)
        assertEquals(1, report.groupsMatched)
        assertEquals(1, report.nodesRemoved)
        assertTrue(doc.select("div.paywall").isEmpty())
        assertEquals("Story", doc.selectFirst("article")!!.text())
    }
}
