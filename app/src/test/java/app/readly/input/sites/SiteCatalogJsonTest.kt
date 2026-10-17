package app.readly.input.sites

import app.readly.input.sites.SiteTestFixtures.entry
import app.readly.input.sites.SiteTestFixtures.import
import app.readly.input.sites.SiteTestFixtures.nytEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Cache-codec strictness: round-trips exactly, then rejects every
 * authority-broadening tamper the importer would never produce.
 */
class SiteCatalogJsonTest {
    private fun roundTrip(vararg entries: String): SiteCatalog {
        val catalog = import(*entries)
        val decoded = SiteCatalogJson.decode(SiteCatalogJson.encode(catalog))
        assertEquals(catalog, decoded)
        return decoded
    }

    @Test
    fun roundTripPreservesCatalog() {
        roundTrip(
            nytEntry(),
            entry(
                "Group",
                "    domain: \"###_g\",\n" +
                    "    group: [\"a.example.com\", \"b.example.com\"],\n" +
                    "    useragent: \"googlebot\",\n" +
                    "    excluded_domains: [\"x.a.example.com\"],\n" +
                    "    cs_code: [{\"hide_elem\": \"div.wall\"}],\n" +
                    "    exception: [{domain: \"b.example.com\", referer: \"google\"}]",
            ),
            entry("Pseudo", "    domain: \"###\""),
            entry("Future", "    domain: \"f.example.com\",\n    ld_json: \"div.w|article\""),
        )
    }

    @Test
    fun unknownFieldsAreTolerated() {
        val catalog = import(nytEntry())
        val json = JSONObject(SiteCatalogJson.encode(catalog))
        json.put("futureTop", 1)
        json.getJSONArray("rules").getJSONObject(0).put("futureRule", "x")
        val decoded = SiteCatalogJson.decode(json.toString())
        assertEquals(catalog, decoded)
    }

    private fun tamperedRule(catalog: SiteCatalog, mutate: (JSONObject) -> Unit): String {
        val json = JSONObject(SiteCatalogJson.encode(catalog))
        mutate(json.getJSONArray("rules").getJSONObject(0))
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
    fun knownLabelValuesMustMatchExactly() {
        val catalog = import(entry("G", "    domain: \"g.example.com\",\n    useragent: \"googlebot\""))
        expectReject(
            tamperedRule(catalog) { it.getJSONObject("request").put("userAgent", "Evil/1.0") },
            "does not match",
        )
        expectReject(
            tamperedRule(catalog) { it.getJSONObject("request").put("referer", "https://evil.example/") },
            "does not match",
        )
        expectReject(
            tamperedRule(catalog) { it.getJSONObject("request").put("userAgentSource", "chrome") },
            "unknown",
        )
    }

    @Test
    fun forbiddenAndInvalidHeadersRejected() {
        val catalog = import(entry("G", "    domain: \"g.example.com\",\n    useragent: \"googlebot\""))
        expectReject(
            tamperedRule(catalog) { it.getJSONObject("request").getJSONObject("extraHeaders").put("Cookie", "x") },
            "must not set",
        )
        expectReject(
            tamperedRule(catalog) { it.getJSONObject("request").getJSONObject("extraHeaders").put("X-A", "") },
            "empty",
        )
        expectReject(
            tamperedRule(catalog) { it.getJSONObject("request").put("userAgent", "has\nnewline") },
            "single line",
        )
    }

    @Test
    fun invalidAndDuplicateDomainsRejected() {
        val catalog = import(entry("G", "    domain: \"g.example.com\",\n    useragent: \"googlebot\""))
        expectReject(
            tamperedRule(catalog) { it.put("matchDomains", org.json.JSONArray(listOf("*.evil.com"))) },
            "wildcard",
        )
        expectReject(
            tamperedRule(catalog) { it.put("matchDomains", org.json.JSONArray(listOf("com"))) },
            "public-suffix-like",
        )
        val two = import(
            entry("A", "    domain: \"a.example.com\",\n    useragent: \"googlebot\""),
            entry("B", "    domain: \"b.example.com\",\n    useragent: \"googlebot\""),
        )
        val json = JSONObject(SiteCatalogJson.encode(two))
        json.getJSONArray("rules").getJSONObject(1).put("matchDomains", org.json.JSONArray(listOf("a.example.com")))
        expectReject(json.toString(), "already claimed")
    }

    @Test
    fun compatScopeCannotBeDowngradedInCache() {
        val catalog = import(nytEntry())
        expectReject(
            tamperedRule(catalog) { it.put("scope", "SUBTREE") },
            "APEX_AND_WWW",
        )
    }

    @Test
    fun invalidSelectorsAndNamesRejected() {
        val catalog = import(
            entry("C", "    domain: \"c.example.com\",\n    cs_code: [{\"hide_elem\": \"div.ok\"}]"),
        )
        expectReject(
            tamperedRule(catalog) {
                it.getJSONObject("html").getJSONArray("cleanup").getJSONObject(0).put("selector", "div[")
            },
            "not valid",
        )
        expectReject(
            tamperedRule(catalog) {
                it.getJSONObject("html").getJSONArray("cleanup").getJSONObject(0).put("kind", "DEFACE")
            },
            "unknown kind",
        )
    }

    @Test
    fun provenanceAndSchemaStrict() {
        val catalog = import(nytEntry())
        val base = JSONObject(SiteCatalogJson.encode(catalog))
        fun withMeta(mutator: (JSONObject) -> Unit): String {
            val copy = JSONObject(base.toString())
            mutator(copy.getJSONObject("meta"))
            return copy.toString()
        }
        val schemaBad = JSONObject(base.toString())
        schemaBad.put("schema", 99)
        expectReject(schemaBad.toString(), "unsupported schema")
        expectReject(withMeta { it.put("version", "soon") }, "invalid \"version\"")
        expectReject(withMeta { it.put("sourceSha256", "zzz") }, "sourceSha256")
        expectReject(withMeta { it.put("sourceUrl", "https://evil.example/x.zip") }, "sourceUrl")
        expectReject(withMeta { it.put("license", "proprietary") }, "MIT")
    }

    @Test
    fun withheldDomainsRoundTripAndBlockFallback() {
        val catalog = import(
            entry(
                "Parent",
                "    domain: \"example.com\",\n" +
                    "    useragent: \"bingbot\",\n" +
                    "    exception: [{domain: \"child.example.com\", excluded_domains: \"bad\"}]",
            ),
        )
        val decoded = SiteCatalogJson.decode(SiteCatalogJson.encode(catalog))
        assertEquals(catalog, decoded)
        assertEquals(setOf("child.example.com"), decoded.withheldDomains)
        assertTrue(decoded.findRule("child.example.com") == null)
        assertTrue(decoded.findRule("example.com") != null)
    }

    @Test
    fun withheldDomainsValidated() {
        val catalog = import(entry("G", "    domain: \"g.example.com\",\n    useragent: \"googlebot\""))
        fun withWithheld(value: Any?): String {
            val json = JSONObject(SiteCatalogJson.encode(catalog))
            if (value == null) json.put("withheldDomains", JSONObject.NULL) else json.put("withheldDomains", value)
            return json.toString()
        }
        expectReject(
            withWithheld(org.json.JSONArray(listOf("*.evil.com"))),
            "wildcard",
        )
        expectReject(
            withWithheld(org.json.JSONArray(listOf("g.example.com"))),
            "also claimed",
        )
        expectReject(
            withWithheld(org.json.JSONArray(listOf("a.example.com", "a.example.com"))),
            "duplicate withheld",
        )
        // Missing key stays readable (old bundles predate barriers).
        val noKey = JSONObject(SiteCatalogJson.encode(catalog))
        noKey.remove("withheldDomains")
        assertEquals(emptySet<String>(), SiteCatalogJson.decode(noKey.toString()).withheldDomains)
    }

    @Test
    fun unicodeHeaderNameRejected() {
        val catalog = import(entry("G", "    domain: \"g.example.com\",\n    useragent: \"googlebot\""))
        expectReject(
            tamperedRule(catalog) {
                it.getJSONObject("request").getJSONObject("extraHeaders").put("X-Ĥeader", "v")
            },
            "invalid name",
        )
    }

    @Test
    fun multiActionCleanupRoundTrips() {
        roundTrip(
            entry(
                "C",
                "    domain: \"c.example.com\",\n" +
                    "    cs_code: [{" +
                    "\"cond\": \".a\", \"rm_elem\": 1, \"rm_class\": \"x\", \"rm_attrib\": \"y\", " +
                    "\"elems\": [{\"hide_elem\": \".b\"}]}]",
            ),
        )
    }

    @Test
    fun sharedCapsStayInSyncWithNytParser() {
        assertEquals(
            app.readly.input.nyt.NytRuleParser.MAX_COMPRESSED_BYTES,
            UpdateArchive.MAX_COMPRESSED_BYTES,
        )
        assertEquals(
            app.readly.input.nyt.NytRuleParser.MAX_TOTAL_UNCOMPRESSED_BYTES,
            UpdateArchive.MAX_TOTAL_UNCOMPRESSED_BYTES,
        )
        assertEquals(
            app.readly.input.nyt.NytRuleParser.MAX_SITES_JS_BYTES,
            UpdateArchive.MAX_SITES_JS_BYTES,
        )
        assertEquals(
            app.readly.input.nyt.NytRuleParser.MAX_MANIFEST_BYTES,
            UpdateArchive.MAX_MANIFEST_BYTES,
        )
        assertEquals(
            app.readly.input.nyt.NytRuleParser.MAX_ENTRIES,
            UpdateArchive.MAX_ENTRIES,
        )
        assertEquals(
            app.readly.input.nyt.NytPolicy.OFFICIAL_UPDATE_URL,
            SiteCatalog.OFFICIAL_UPDATE_URL,
        )
    }
}
