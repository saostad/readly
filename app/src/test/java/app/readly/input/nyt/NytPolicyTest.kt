package app.readly.input.nyt

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NytPolicyTest {

    private fun policy(
        userAgent: String = "UA",
        allowCookies: Boolean = true,
        blockPattern: String? = "block",
        version: String = "4.4.7.4",
        sha: String = "",
        url: String = NytPolicy.OFFICIAL_UPDATE_URL,
    ): NytPolicy = NytPolicy(userAgent, allowCookies, blockPattern, version, sha, url)

    private fun decodeFailsWith(json: String): String {
        try {
            NytPolicyJson.decode(json)
        } catch (e: NytPolicyException) {
            return e.message ?: "(no message)"
        }
        fail("expected NytPolicyException for: $json")
        throw AssertionError("unreachable")
    }

    private fun mutate(mutator: (JSONObject) -> Unit): String {
        val o = JSONObject(NytPolicyJson.encode(policy()))
        mutator(o)
        return o.toString()
    }

    @Test
    fun encodeDecode_roundTrip() {
        val full = policy(sha = "ab".repeat(32))
        assertEquals(full, NytPolicyJson.decode(NytPolicyJson.encode(full)))
        val minimal = policy(blockPattern = null, sha = "", allowCookies = false)
        assertEquals(minimal, NytPolicyJson.decode(NytPolicyJson.encode(minimal)))
    }

    @Test
    fun decode_rejectsBadSchema() {
        assertTrue("missing", "schema" in decodeFailsWith(mutate { it.remove("schema") }))
        assertTrue("wrong", "schema" in decodeFailsWith(mutate { it.put("schema", 2) }))
        assertTrue("string", "schema" in decodeFailsWith(mutate { it.put("schema", "1") }))
        assertTrue("double", "schema" in decodeFailsWith(mutate { it.put("schema", 1.5) }))
    }

    @Test
    fun decode_rejectsWrongDomain() {
        val message = decodeFailsWith(mutate { it.put("domain", "example.com") })
        assertTrue("message was: $message", "domain" in message && "example.com" in message)
    }

    @Test
    fun decode_rejectsBadUserAgent() {
        val blank = decodeFailsWith(mutate { it.put("userAgent", "  ") })
        assertTrue("message was: $blank", "blank" in blank)
        val newline = decodeFailsWith(mutate { it.put("userAgent", "a\nb") })
        assertTrue("message was: $newline", "single line" in newline)
        val cr = decodeFailsWith(mutate { it.put("userAgent", "a\rb") })
        assertTrue("message was: $cr", "single line" in cr)
        val long = decodeFailsWith(mutate { it.put("userAgent", "x".repeat(513)) })
        assertTrue("message was: $long", "too long" in long)
        // Boundary: 512 chars is accepted.
        NytPolicyJson.decode(mutate { it.put("userAgent", "x".repeat(512)) })
    }

    @Test
    fun decode_rejectsInvalidHeaderChars() {
        val cases = listOf(
            "a\u0000b" to "U+0000",
            "a\u0001b" to "U+0001",
            "a\tb" to "U+0009",
            "a\u007Fb" to "U+007F",
            "a\u0100b" to "U+0100",
        )
        for ((value, code) in cases) {
            val message = decodeFailsWith(mutate { it.put("userAgent", value) })
            assertTrue(
                "value $code gave: $message",
                "invalid HTTP header character" in message && code in message,
            )
        }
        // obs-text stays accepted.
        NytPolicyJson.decode(mutate { it.put("userAgent", "AéB") })
    }

    @Test
    fun decode_allowCookiesForms() {
        assertEquals(true, NytPolicyJson.decode(mutate { it.put("allowCookies", true) }).allowCookies)
        assertEquals(false, NytPolicyJson.decode(mutate { it.put("allowCookies", false) }).allowCookies)
        assertEquals(true, NytPolicyJson.decode(mutate { it.put("allowCookies", 1) }).allowCookies)
        assertEquals(false, NytPolicyJson.decode(mutate { it.put("allowCookies", 0) }).allowCookies)
        val str = decodeFailsWith(mutate { it.put("allowCookies", "yes") })
        assertTrue("message was: $str", "must be true/false or 0/1" in str)
        val two = decodeFailsWith(mutate { it.put("allowCookies", 2) })
        assertTrue("message was: $two", "unsupported value" in two)
        val dbl = decodeFailsWith(mutate { it.put("allowCookies", 1.5) })
        assertTrue("message was: $dbl", "must be true/false or 0/1" in dbl)
    }

    @Test
    fun decode_rejectsBadVersion() {
        for (bad in listOf("abc", "", "4..7", "v1")) {
            val message = decodeFailsWith(mutate { it.put("sourceVersion", bad) })
            assertTrue("$bad gave: $message", "sourceVersion" in message)
        }
        assertTrue("missing" in decodeFailsWith(mutate { it.remove("sourceVersion") }))
    }

    @Test
    fun decode_shaForms() {
        NytPolicyJson.decode(mutate { it.put("sourceSha256", "") })
        NytPolicyJson.decode(mutate { it.put("sourceSha256", "ab".repeat(32)) })
        val short = decodeFailsWith(mutate { it.put("sourceSha256", "xyz") })
        assertTrue("message was: $short", "sourceSha256" in short)
        val upper = decodeFailsWith(mutate { it.put("sourceSha256", "AB".repeat(32)) })
        assertTrue("message was: $upper", "sourceSha256" in upper)
    }

    @Test
    fun decode_rejectsOffSourceUrl() {
        val message = decodeFailsWith(mutate { it.put("sourceUrl", "https://evil.example/x.zip") })
        assertTrue("message was: $message", "sourceUrl" in message)
        assertTrue("missing" in decodeFailsWith(mutate { it.remove("sourceUrl") }))
    }

    @Test
    fun decode_ignoresUnknownFields() {
        val json = mutate {
            it.put("attribution", "some project")
            it.put("license", "MIT License text")
            it.put("futureField", 123)
        }
        assertEquals("UA", NytPolicyJson.decode(json).userAgent)
    }

    @Test
    fun decode_invalidJson_namesRealError() {
        val message = decodeFailsWith("{oops")
        assertTrue("message was: $message", "JSONException" in message)
    }

    @Test
    fun compareVersions_ordersNumerically() {
        assertTrue(NytPolicy.compareVersions("4.10", "4.9") > 0)
        assertTrue(NytPolicy.compareVersions("4.9", "4.10") < 0)
        assertEquals(0, NytPolicy.compareVersions("4.4.7.4", "4.4.7.4"))
        assertEquals(0, NytPolicy.compareVersions("4.4", "4.4.0"))
        assertEquals(0, NytPolicy.compareVersions("04.4", "4.4"))
        assertTrue(NytPolicy.compareVersions("4.4.7.4", "4.4.7") > 0)
        assertTrue(NytPolicy.compareVersions("4.4.7", "4.4.7.4") < 0)
        assertTrue(NytPolicy.compareVersions("10", "9") > 0)
    }

    @Test
    fun isValidVersion_table() {
        for (good in listOf("4", "4.4.7.4", "0", "123456", "1.2.3.4.5.6.7.8")) {
            assertTrue("$good should be valid", NytPolicy.isValidVersion(good))
        }
        for (bad in listOf("", "4.", ".4", "4..4", "v1", "1.2.3.4.5.6.7.8.9", "1234567", "4,4")) {
            assertFalse("$bad should be invalid", NytPolicy.isValidVersion(bad))
        }
    }

    @Test
    fun isSupportedHost_exactHosts() {
        assertTrue(NytPolicy.isSupportedHost("nytimes.com"))
        assertTrue(NytPolicy.isSupportedHost("www.nytimes.com"))
        assertTrue(NytPolicy.isSupportedHost("WWW.NYTIMES.COM"))
        assertFalse(NytPolicy.isSupportedHost("mobile.nytimes.com"))
        assertFalse(NytPolicy.isSupportedHost("nytimes.com.evil.com"))
        assertFalse(NytPolicy.isSupportedHost("evil.com"))
        assertFalse(NytPolicy.isSupportedHost(""))
        assertFalse(NytPolicy.isSupportedHost(null))
    }

    @Test
    fun bundledAsset_parsesAndMatchesInspectedRule() {
        val userDir = System.getProperty("user.dir")
        val candidates = listOf(
            "src/main/assets/nyt/nyt_policy.json",
            "app/src/main/assets/nyt/nyt_policy.json",
        )
        val file = candidates.map { File(userDir, it) }.firstOrNull { it.exists() }
        assertTrue("bundled asset not found; tried $candidates under $userDir", file != null)
        val json = file!!.readText(Charsets.UTF_8)
        val policy = NytPolicyJson.decode(json, "Bundled NYT policy")
        assertEquals("Mozilla/5.0 (compatible; Google-InspectionTool/1.0)", policy.userAgent)
        assertEquals(true, policy.allowCookies)
        assertEquals(
            "(\\.nytimes\\.com\\/(meter\\.js|svc\\/onsite-messaging\\/query)|" +
                "mwcm\\.nyt\\.com\\/.+\\.js|cooking\\.nytimes\\.com\\/api\\/.+\\/access)",
            policy.blockPattern,
        )
        assertEquals("4.4.7.4", policy.sourceVersion)
        assertEquals(NytPolicy.OFFICIAL_UPDATE_URL, policy.sourceUrl)
        val raw = JSONObject(json)
        val attribution = raw.optString("attribution", "")
        assertTrue("attribution was: $attribution", "Bypass Paywalls Clean" in attribution)
        val license = raw.optString("license", "")
        assertTrue("license missing header", license.startsWith("MIT License"))
        assertTrue("license missing holder", "magnolia1234" in license)
        assertTrue("license truncated", "WITHOUT WARRANTY" in license && license.trimEnd().endsWith("SOFTWARE."))
    }
}
