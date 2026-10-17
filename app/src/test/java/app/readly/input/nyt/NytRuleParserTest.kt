package app.readly.input.nyt

import app.readly.input.nyt.NytTestFixtures.BLOCK
import app.readly.input.nyt.NytTestFixtures.SHA
import app.readly.input.nyt.NytTestFixtures.TOP
import app.readly.input.nyt.NytTestFixtures.UA
import app.readly.input.nyt.NytTestFixtures.VERSION
import app.readly.input.nyt.NytTestFixtures.entry
import app.readly.input.nyt.NytTestFixtures.manifestJson
import app.readly.input.nyt.NytTestFixtures.manifestWithoutVersion
import app.readly.input.nyt.NytTestFixtures.nytBody
import app.readly.input.nyt.NytTestFixtures.nytEntry
import app.readly.input.nyt.NytTestFixtures.parseFailure
import app.readly.input.nyt.NytTestFixtures.parseOk
import app.readly.input.nyt.NytTestFixtures.realStyleSitesJs
import app.readly.input.nyt.NytTestFixtures.sitesJs
import app.readly.input.nyt.NytTestFixtures.updateZip
import app.readly.input.nyt.NytTestFixtures.zipOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NytRuleParserTest {

    private fun failsWith(zip: ByteArray): String = parseFailure(zip).message ?: "(no message)"

    private fun failsAsIncompatible(zip: ByteArray): String {
        val e = parseFailure(zip)
        assertTrue("expected NytIncompatibleException, was ${e.javaClass.name}", e is NytIncompatibleException)
        return e.message ?: "(no message)"
    }

    @Test
    fun realFixture_returnsPolicy() {
        val policy = parseOk(updateZip())
        assertEquals(UA, policy.userAgent)
        assertEquals(true, policy.allowCookies)
        assertEquals(BLOCK, policy.blockPattern)
        assertEquals(VERSION, policy.sourceVersion)
        assertEquals(SHA, policy.sourceSha256)
        assertEquals(NytPolicy.OFFICIAL_UPDATE_URL, policy.sourceUrl)
    }

    @Test
    fun flatEntries_withoutTopDir_parse() {
        val zip = zipOf(
            "sites.js" to realStyleSitesJs().toByteArray(),
            "manifest.json" to manifestJson().toByteArray(),
        )
        assertEquals(UA, parseOk(zip).userAgent)
    }

    @Test
    fun absentNytEntry_fails() {
        val zip = updateZip(sitesJs(entry("Other", "    domain: \"example.com\"")))
        val message = failsAsIncompatible(zip)
        assertTrue("message was: $message", "has no \"The New York Times\" entry" in message)
    }

    @Test
    fun duplicateNytEntry_fails() {
        val zip = updateZip(sitesJs(nytEntry(), nytEntry()))
        val message = failsAsIncompatible(zip)
        assertTrue("message was: $message", "duplicate \"The New York Times\" entries" in message)
    }

    @Test
    fun stringEscapesAndBraces_decoded() {
        val body = nytBody(userAgent = "\"A\\\"{B}\\\\C\\u0021\"")
        val wrapped = sitesJs(
            entry("Brace", "    note: \"}{\""),
            nytEntry(body),
        )
        val policy = parseOk(updateZip(wrapped))
        assertEquals("A\"{B}\\C!", policy.userAgent)
    }

    @Test
    fun regexClassAndEscapes_scannedExactly() {
        // A naive scan would end the literal at the `/` in the class, the
        // quote, or split fields at `,`/`:`; braces would unbalance depth.
        val source = """x[}/,:"']y\/z{2}"""
        val policy = parseOk(updateZip(realStyleSitesJs(nytBody(block = "/$source/"))))
        assertEquals(source, policy.blockPattern)
    }

    @Test
    fun regexFlags_acceptedSourceKept() {
        val policy = parseOk(updateZip(realStyleSitesJs(nytBody(block = "/abc/gi"))))
        assertEquals("abc", policy.blockPattern)
    }

    @Test
    fun wrongDomain_rejected() {
        val zip = updateZip(realStyleSitesJs(nytBody(domain = "\"example.com\"")))
        val message = failsAsIncompatible(zip)
        assertTrue("message was: $message", "unexpected domain" in message && "example.com" in message)
    }

    @Test
    fun userAgentNewline_rejected() {
        val zip = updateZip(realStyleSitesJs(nytBody(userAgent = "\"A\\nB\"")))
        assertTrue(failsAsIncompatible(zip).contains("single line"))
    }

    @Test
    fun userAgentBlank_rejected() {
        val zip = updateZip(realStyleSitesJs(nytBody(userAgent = "\"   \"")))
        assertTrue(failsAsIncompatible(zip).contains("blank"))
    }

    @Test
    fun userAgentTooLong_rejected() {
        val zip = updateZip(realStyleSitesJs(nytBody(userAgent = "\"${"x".repeat(513)}\"")))
        assertTrue(failsAsIncompatible(zip).contains("too long"))
    }

    @Test
    fun dynamicIdentifierValue_rejected() {
        val zip = updateZip(realStyleSitesJs(nytBody(userAgent = "pickUA()")))
        val message = failsAsIncompatible(zip)
        assertTrue("message was: $message", "dynamic value" in message && "pickUA" in message)
    }

    @Test
    fun dynamicBinaryExpression_rejected() {
        val zip = updateZip(realStyleSitesJs(nytBody(allowCookies = "1+1")))
        assertTrue(failsAsIncompatible(zip).contains("dynamic expression"))
    }

    @Test
    fun unsupportedField_rejected() {
        val zip = updateZip(realStyleSitesJs(nytBody(extra = "cs_dompurify: 1")))
        val message = failsAsIncompatible(zip)
        assertTrue("message was: $message", "unsupported field" in message && "cs_dompurify" in message)
    }

    @Test
    fun wrongTypes_rejected() {
        val cases = listOf(
            nytBody(domain = "1") to "must be a string",
            nytBody(allowCookies = "\"1\"") to "must be a number",
            nytBody(block = "\"x\"") to "must be a regex literal",
            nytBody(userAgent = "7") to "must be a string",
        )
        for ((body, fragment) in cases) {
            val message = failsAsIncompatible(updateZip(realStyleSitesJs(body)))
            assertTrue("message was: $message", fragment in message)
        }
    }

    @Test
    fun missingRequiredFields_rejected() {
        val noDomain = failsAsIncompatible(updateZip(realStyleSitesJs(nytBody(domain = null))))
        assertTrue("message was: $noDomain", "missing required field \"domain\"" in noDomain)
        val noUa = failsAsIncompatible(updateZip(realStyleSitesJs(nytBody(userAgent = null))))
        assertTrue("message was: $noUa", "missing required field \"useragent_custom\"" in noUa)
    }

    @Test
    fun optionalFields_defaulted() {
        val policy = parseOk(updateZip(realStyleSitesJs(nytBody(allowCookies = null, block = null))))
        assertFalse(policy.allowCookies)
        assertNull(policy.blockPattern)
    }

    @Test
    fun allowCookiesZero_isFalse() {
        val policy = parseOk(updateZip(realStyleSitesJs(nytBody(allowCookies = "0"))))
        assertFalse(policy.allowCookies)
    }

    @Test
    fun allowCookiesTwo_rejected() {
        val zip = updateZip(realStyleSitesJs(nytBody(allowCookies = "2")))
        assertTrue(failsAsIncompatible(zip).contains("expected 0 or 1"))
    }

    @Test
    fun commentsAndTrailingComma_accepted() {
        val body = "    domain: \"nytimes.com\", // trailing\n" +
            "    /* block */ allow_cookies: 1,\n" +
            "    block_regex: /x/, // c\n" +
            "    useragent_custom: \"$UA\", // trailing comma below is fine\n"
        val policy = parseOk(updateZip(sitesJs(nytEntry(body))))
        assertEquals(UA, policy.userAgent)
        assertEquals("x", policy.blockPattern)
    }

    @Test
    fun singleQuotedKeyAndValues_accepted() {
        val text = "var defaultSites = {\n" +
            "  'The New York Times': {\n" +
            "    domain: 'nytimes.com',\n" +
            "    allow_cookies: 1,\n" +
            "    useragent_custom: 'UA'\n" +
            "  }\n};\n"
        val policy = parseOk(updateZip(text))
        assertEquals("UA", policy.userAgent)
    }

    @Test
    fun quotedFieldNames_accepted() {
        val body = "    \"domain\": \"nytimes.com\",\n" +
            "    \"allow_cookies\": 1,\n" +
            "    \"useragent_custom\": \"$UA\"\n"
        assertEquals(UA, parseOk(updateZip(sitesJs(nytEntry(body)))).userAgent)
    }

    @Test
    fun missingSitesJs_fails() {
        val zip = zipOf("${TOP}manifest.json" to manifestJson().toByteArray())
        assertTrue(failsWith(zip).contains("has no \"sites.js\""))
    }

    @Test
    fun missingManifest_fails() {
        val zip = zipOf("${TOP}sites.js" to realStyleSitesJs().toByteArray())
        assertTrue(failsWith(zip).contains("has no \"manifest.json\" next to"))
    }

    @Test
    fun duplicateSitesJs_fails() {
        val zip = zipOf(
            "${TOP}sites.js" to realStyleSitesJs().toByteArray(),
            "other/sites.js" to realStyleSitesJs().toByteArray(),
            "${TOP}manifest.json" to manifestJson().toByteArray(),
        )
        assertTrue(failsWith(zip).contains("duplicate \"sites.js\""))
    }

    @Test
    fun otherDirManifest_ignored() {
        val zip = zipOf(
            "${TOP}sites.js" to realStyleSitesJs().toByteArray(),
            "${TOP}manifest.json" to manifestJson().toByteArray(),
            "${TOP}custom/manifest.json" to manifestJson("\"9.9\"").toByteArray(),
        )
        // The real archive ships extra copies under custom/.
        assertEquals(VERSION, parseOk(zip).sourceVersion)
    }

    @Test
    fun duplicateManifest_fails() {
        // ZipOutputStream refuses same-name entries, so the duplicate local
        // record is spliced in by hand (see NytTestFixtures).
        val base = NytTestFixtures.storedZipOf(
            "${TOP}sites.js" to realStyleSitesJs().toByteArray(),
            "${TOP}manifest.json" to manifestJson().toByteArray(),
        )
        assertEquals(VERSION, parseOk(base).sourceVersion)
        val dupe = NytTestFixtures.withDuplicatedEntry(base, "${TOP}manifest.json")
        assertTrue(failsWith(dupe).contains("duplicate \"manifest.json\""))
    }

    @Test
    fun traversalEntries_ignored() {
        val manifest = manifestJson().toByteArray()
        val traversalOnly = zipOf(
            "../evil/sites.js" to realStyleSitesJs().toByteArray(),
            "${TOP}manifest.json" to manifest,
        )
        assertTrue(failsWith(traversalOnly).contains("has no \"sites.js\""))

        val withValid = zipOf(
            "../evil/sites.js" to "junk".toByteArray(),
            "/sites.js" to "junk".toByteArray(),
            "${TOP}sites.js" to realStyleSitesJs().toByteArray(),
            "${TOP}manifest.json" to manifest,
        )
        assertEquals(VERSION, parseOk(withValid).sourceVersion)
    }

    @Test
    fun compressedCap_rejected() {
        val message = failsWith(ByteArray((NytRuleParser.MAX_COMPRESSED_BYTES + 1).toInt()))
        assertTrue("message was: $message", "over 8 MiB" in message)
    }

    @Test
    fun sitesJsCap_rejected() {
        val big = " ".repeat((NytRuleParser.MAX_SITES_JS_BYTES + 1).toInt())
        val zip = zipOf(
            "${TOP}sites.js" to big.toByteArray(),
            "${TOP}manifest.json" to manifestJson().toByteArray(),
        )
        assertTrue(failsWith(zip).contains("over 2 MiB"))
    }

    @Test
    fun manifestCap_rejected() {
        val big = " ".repeat((NytRuleParser.MAX_MANIFEST_BYTES + 1).toInt())
        val zip = zipOf(
            "${TOP}sites.js" to realStyleSitesJs().toByteArray(),
            "${TOP}manifest.json" to big.toByteArray(),
        )
        assertTrue(failsWith(zip).contains("over 64 KiB"))
    }

    @Test
    fun totalUncompressedCap_rejected() {
        val junk = ByteArray((NytRuleParser.MAX_TOTAL_UNCOMPRESSED_BYTES + 1).toInt())
        val zip = zipOf(
            "${TOP}sites.js" to realStyleSitesJs().toByteArray(),
            "${TOP}manifest.json" to manifestJson().toByteArray(),
            "${TOP}junk.bin" to junk,
        )
        assertTrue(failsWith(zip).contains("over 32 MiB"))
    }

    @Test
    fun entryCountCap_rejected() {
        val files = (0..NytRuleParser.MAX_ENTRIES).map { "f$it" to "x".toByteArray() }
        val message = failsWith(zipOf(*files.toTypedArray()))
        assertTrue("message was: $message", "too many entries" in message)
    }

    @Test
    fun invalidManifestVersions_rejected() {
        val fragments = listOf("\"abc\"", "\"4..7\"", "\"\"", "\"v1\"", "\"1.2.3.4.5.6.7.8.9\"", "\"1234567\"", "4.4")
        for (fragment in fragments) {
            val message = failsAsIncompatible(updateZip(manifestText = manifestJson(fragment)))
            assertTrue("fragment $fragment gave: $message", "invalid version" in message)
        }
        val missing = failsAsIncompatible(updateZip(manifestText = manifestWithoutVersion()))
        assertTrue("message was: $missing", "has no \"version\"" in missing)
    }

    @Test
    fun manifestInvalidJson_fails() {
        val message = failsWith(updateZip(manifestText = "not json"))
        assertTrue("message was: $message", "JSONException" in message)
    }

    @Test
    fun sitesJsInvalidUtf8_fails() {
        val zip = zipOf(
            "${TOP}sites.js" to byteArrayOf(0xFF.toByte(), 0xFE.toByte()),
            "${TOP}manifest.json" to manifestJson().toByteArray(),
        )
        assertTrue(failsWith(zip).contains("not valid UTF-8"))
    }

    @Test
    fun unsupportedValueShapes_rejected() {
        val cases = listOf(
            nytBody(domain = "\"a\" + \"b\"") to "dynamic expression",
            nytBody(allowCookies = "true") to "dynamic value",
            nytBody(block = "[1]") to "nested value",
            nytBody(userAgent = "{a: 1}") to "nested value",
            nytBody(domain = "getDomain()") to "dynamic value",
            nytBody(allowCookies = "1.5") to "unsupported numeric value",
            nytBody(allowCookies = "+1") to "dynamic expression",
            nytBody(userAgent = "`UA`") to "template literal",
            nytBody(allowCookies = "-1") to "expected 0 or 1",
        )
        for ((body, fragment) in cases) {
            val message = failsAsIncompatible(updateZip(realStyleSitesJs(body)))
            assertTrue("body $body gave: $message", fragment in message)
        }
    }

    @Test
    fun octalEscape_rejected() {
        val zip = updateZip(realStyleSitesJs(nytBody(userAgent = "\"A\\1B\"")))
        assertTrue(failsAsIncompatible(zip).contains("octal escape"))
    }

    @Test
    fun nonObjectEntry_rejected() {
        val zip = updateZip(sitesJs("  \"The New York Times\": getRule()"))
        assertTrue(failsAsIncompatible(zip).contains("not a static object"))
    }

    @Test
    fun unterminatedObject_fails() {
        val truncated = "var defaultSites = {\n" +
            "  \"The New York Times\": { domain: \"nytimes.com\","
        val zip = updateZip(truncated)
        assertTrue(failsWith(zip).contains("unterminated"))
    }

    @Test
    fun duplicateField_rejected() {
        val body = "    domain: \"nytimes.com\",\n" +
            "    domain: \"nytimes.com\",\n" +
            "    useragent_custom: \"$UA\"\n"
        assertTrue(failsAsIncompatible(updateZip(sitesJs(nytEntry(body)))).contains("duplicate field"))
    }

    @Test
    fun emptyObject_missingField() {
        val zip = updateZip(sitesJs(nytEntry("")))
        assertTrue(failsAsIncompatible(zip).contains("missing required field"))
    }

    @Test
    fun badZipBytes_fails() {
        val message = failsWith("not a zip".toByteArray())
        assertTrue("message was: $message", "not a ZIP archive" in message)
    }

    @Test
    fun emptyZip_fails() {
        assertTrue(failsWith(zipOf()).contains("has no \"sites.js\""))
    }

    @Test
    fun nestedEntry_rejectedAsMissing() {
        val js = "var defaultSites={Other:{\"The New York Times\":" +
            "{domain:\"nytimes.com\",useragent_custom:\"UA\"}}};" +
            "\n"
        val message = failsAsIncompatible(updateZip(js))
        assertTrue("message was: $message", "has no \"The New York Times\" entry" in message)
    }

    @Test
    fun unrelatedEntry_rejectedAsMissing() {
        val js = "var defaultSites={};var unrelated={\"The New York Times\":" +
            "{domain:\"nytimes.com\",useragent_custom:\"UA\"}};" +
            "\n"
        val message = failsAsIncompatible(updateZip(js))
        assertTrue("message was: $message", "has no \"The New York Times\" entry" in message)
    }

    @Test
    fun propertySuffix_rejected() {
        val js = "var defaultSites={\"The New York Times\":" +
            "{domain:\"nytimes.com\",useragent_custom:\"UA\"}.missing};" +
            "\n"
        val message = failsAsIncompatible(updateZip(js))
        assertTrue("message was: $message", "unsupported suffix" in message)
    }

    @Test
    fun operatorSuffixes_rejected() {
        val suffixes = listOf("(1)", "[0]", "+x", "?a:b")
        for (suffix in suffixes) {
            val js = "var defaultSites={\"The New York Times\":" +
                "{domain:\"nytimes.com\",useragent_custom:\"UA\"}$suffix};" +
                "\n"
            val message = failsAsIncompatible(updateZip(js))
            assertTrue("suffix $suffix gave: $message", "unsupported suffix" in message)
        }
    }

    @Test
    fun genuinePlusDecoys_acceptsGenuine() {
        val genuine = nytEntry()
        val nestedDecoy = entry(
            "Other",
            "    child: {\"The New York Times\": " +
                "{domain:\"evil.example\", useragent_custom:\"EVIL\"}}",
        )
        val js = sitesJs(nestedDecoy, genuine) +
            "var unrelated={\"The New York Times\":" +
            "{domain:\"evil.example\",useragent_custom:\"EVIL2\"}};\n"
        val policy = parseOk(updateZip(js))
        assertEquals(UA, policy.userAgent)
        assertEquals(VERSION, policy.sourceVersion)
    }

    @Test
    fun decoysWithBraces_doNotBreakDirectScan() {
        val decoy = entry("Decoy", "    note: \"}{[(\",\n    re: /[}/]+/,\n    n: 1")
        val js = sitesJs(decoy, nytEntry(), entry("After", "    domain: \"example.com\""))
        assertEquals(UA, parseOk(updateZip(js)).userAgent)
    }

    @Test
    fun duplicateGenuine_rejected() {
        val js = sitesJs(nytEntry(), nytEntry())
        assertTrue(failsAsIncompatible(updateZip(js)).contains("duplicate"))
    }

    @Test
    fun nestedOnly_isMissingNotDuplicate() {
        val js = sitesJs(
            entry("A", "    x: {\"The New York Times\": {domain:\"a\", useragent_custom:\"A\"}}"),
            entry("B", "    y: [{\"The New York Times\": {domain:\"b\", useragent_custom:\"B\"}}]"),
        )
        val message = failsAsIncompatible(updateZip(js))
        assertTrue("message was: $message", "has no \"The New York Times\" entry" in message)
    }

    @Test
    fun staticRootGrammarErrors_rejected() {
        val noRoot = failsAsIncompatible(updateZip("var x = {};\n"))
        assertTrue("message was: $noRoot", "has no \"defaultSites\"" in noRoot)
        val multi = "var defaultSites = {\n" + nytEntry() + "\n};\n" +
            "var defaultSites = {\n" + nytEntry() + "\n};\n"
        val multiMsg = failsAsIncompatible(updateZip(multi))
        assertTrue("message was: $multiMsg", "multiple \"defaultSites\"" in multiMsg)
        val template = "var defaultSites = {\n" + nytEntry() + "\n};\nvar t = `hi`;\n"
        assertTrue(failsAsIncompatible(updateZip(template)).contains("template literal"))
        val unbalanced = "var defaultSites = { a: 1 ] };\n"
        assertTrue(failsAsIncompatible(updateZip(unbalanced)).contains("unexpected ']'"))
    }

    @Test
    fun rootDeclarations_letAndConstAccepted() {
        for (keyword in listOf("let", "const")) {
            val js = "$keyword defaultSites = {\n" + nytEntry() + "\n};\n"
            val policy = parseOk(updateZip(js))
            assertEquals(UA, policy.userAgent)
            assertEquals(VERSION, policy.sourceVersion)
        }
    }

    @Test
    fun rootDeclaration_memberAndBareAndNestedRejected() {
        val genuine = nytEntry()
        val member = "var obj = {};\nobj.defaultSites = {\n$genuine\n};\n"
        assertTrue(
            "member gave: ${failsAsIncompatible(updateZip(member))}",
            "has no \"defaultSites\"" in failsAsIncompatible(updateZip(member)),
        )
        val bare = "defaultSites = {\n$genuine\n};\n"
        assertTrue(
            "bare gave: ${failsAsIncompatible(updateZip(bare))}",
            "has no \"defaultSites\"" in failsAsIncompatible(updateZip(bare)),
        )
        val nested = "function f() {\nvar defaultSites = {\n$genuine\n};\n}\n"
        assertTrue(
            "nested gave: ${failsAsIncompatible(updateZip(nested))}",
            "has no \"defaultSites\"" in failsAsIncompatible(updateZip(nested)),
        )
        // A genuine top-level declaration still wins over member/nested decoys.
        val mixed = "var obj = {};\nobj.defaultSites = {\"x\": 1};\n" +
            "function f() { var defaultSites = {\"y\": 2}; }\n" +
            "var defaultSites = {\n$genuine\n};\n"
        assertEquals(UA, parseOk(updateZip(mixed)).userAgent)
    }

    @Test
    fun rootDeclaration_suffixRejected() {
        val genuine = nytEntry()
        for (suffix in listOf(".missing", "(1)", "[0]", "+x")) {
            val js = "var defaultSites = {\n$genuine\n}$suffix;\n"
            val message = failsAsIncompatible(updateZip(js))
            assertTrue("suffix $suffix gave: $message", "unsupported suffix" in message)
        }
        // Statement end, declaration list and EOF all still parse.
        assertEquals(UA, parseOk(updateZip("var defaultSites = {\n$genuine\n}\n")).userAgent)
        val trailing = "var defaultSites = {\n$genuine\n}, other = 1;\n"
        assertEquals(UA, parseOk(updateZip(trailing)).userAgent)
    }

    @Test
    fun userAgentHeaderChars_rejected() {
        val cases = listOf(
            "\"A\\u0000B\"" to "U+0000",
            "\"A\\x01B\"" to "U+0001",
            "\"A\\tB\"" to "U+0009",
            "\"A\\u007FB\"" to "U+007F",
            "\"A\\u0100B\"" to "U+0100",
        )
        for ((fragment, code) in cases) {
            val message = failsAsIncompatible(updateZip(realStyleSitesJs(nytBody(userAgent = fragment))))
            assertTrue(
                "fragment $fragment gave: $message",
                "invalid HTTP header character" in message && code in message,
            )
        }
        // obs-text stays accepted.
        val ok = parseOk(updateZip(realStyleSitesJs(nytBody(userAgent = "\"A\\xE9B\""))))
        assertEquals("A\u00E9B", ok.userAgent)
    }
}
