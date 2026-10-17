package app.readly.input.nyt

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.fail

/**
 * Small representative fixtures for the NYT rule tests: a realistic entry
 * text plus in-memory ZIP builders. Never the full upstream code.
 */
internal object NytTestFixtures {
    const val TOP = "bypass-paywalls-chrome-clean-master/"
    const val UA = "Mozilla/5.0 (compatible; Google-InspectionTool/1.0)"
    const val BLOCK =
        "(\\.nytimes\\.com\\/(meter\\.js|svc\\/onsite-messaging\\/query)|" +
            "mwcm\\.nyt\\.com\\/.+\\.js|cooking\\.nytimes\\.com\\/api\\/.+\\/access)"
    const val VERSION = "4.4.7.4"
    const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    /** One `sites.js` site entry from a raw field body. */
    fun entry(key: String, body: String): String = "  \"$key\": {\n$body\n  }"

    /**
     * Standard NYT field body. Each argument is a raw value fragment, so
     * tests can inject dynamic expressions or wrong types; null omits.
     */
    fun nytBody(
        domain: String? = "\"nytimes.com\"",
        allowCookies: String? = "1",
        block: String? = "/$BLOCK/",
        userAgent: String? = "\"$UA\"",
        extra: String? = null,
    ): String {
        val parts = mutableListOf<String>()
        if (domain != null) parts.add("    domain: $domain")
        if (allowCookies != null) parts.add("    allow_cookies: $allowCookies")
        if (block != null) parts.add("    block_regex: $block")
        if (userAgent != null) parts.add("    useragent_custom: $userAgent")
        if (extra != null) parts.add("    $extra")
        return parts.joinToString(",\n")
    }

    fun nytEntry(body: String = nytBody()): String = entry(NytPolicy.SITE_KEY, body)

    fun sitesJs(vararg entries: String, prefix: String = "", suffix: String = ""): String =
        prefix + "var defaultSites = {\n" + entries.joinToString(",\n") + "\n};\n" + suffix

    /**
     * Realistic wrapper: mentions of the key inside another string, a line
     * comment, a block comment and a plain string statement must all be
     * ignored; only the real entry parses.
     */
    fun realStyleSitesJs(nytBodyText: String = nytBody()): String = sitesJs(
        entry("Decoy", "    note: \"The New York Times\""),
        nytEntry(nytBodyText),
        entry("After", "    domain: \"example.com\""),
        prefix = "var x = \"The New York Times\";\n" +
            "// \"The New York Times\": { domain: \"commented.out\" }\n" +
            "/* \"The New York Times\": { domain: \"blocked.out\" } */\n",
        suffix = "var nofix_sites = [];\n",
    )

    /** [versionFragment] is raw JSON (`"4.4.7.4"`, `4.4`, ...) for negative tests. */
    fun manifestJson(versionFragment: String = "\"$VERSION\""): String =
        "{\"name\": \"Bypass Paywalls Clean\", \"version\": $versionFragment}"

    fun manifestWithoutVersion(): String = "{\"name\": \"Bypass Paywalls Clean\"}"

    fun zipOf(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun updateZip(
        sitesJsText: String = realStyleSitesJs(),
        manifestText: String = manifestJson(),
        top: String = TOP,
    ): ByteArray = zipOf(
        "${top}sites.js" to sitesJsText.toByteArray(Charsets.UTF_8),
        "${top}manifest.json" to manifestText.toByteArray(Charsets.UTF_8),
    )

    /** All-STORED archive so local records have known sizes for splicing. */
    fun storedZipOf(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in files) {
                val entry = ZipEntry(name)
                entry.method = ZipEntry.STORED
                entry.size = data.size.toLong()
                entry.compressedSize = data.size.toLong()
                val crc = CRC32()
                crc.update(data)
                entry.crc = crc.value
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * Returns [zip] (built by [storedZipOf]) with the local record of
     * [entryName] spliced in a second time, just before the central
     * directory. The streaming reader sees the duplicate; the stale central
     * directory behind it is never consulted.
     */
    fun withDuplicatedEntry(zip: ByteArray, entryName: String): ByteArray {
        fun u16(at: Int): Int =
            (zip[at].toInt() and 0xFF) or ((zip[at + 1].toInt() and 0xFF) shl 8)
        fun u32(at: Int): Long =
            (0..3).fold(0L) { acc, k -> acc or ((zip[at + k].toLong() and 0xFF) shl (8 * k)) }
        require(zip.size >= 22) { "zip too small" }
        val endAt = zip.size - 22
        require(u32(endAt) == 0x06054b50L) { "no END record" }
        val cdAt = u32(endAt + 16).toInt()
        var at = 0
        var dup: ByteArray? = null
        while (at < cdAt) {
            require(u32(at) == 0x04034b50L) { "expected local header at $at" }
            val compSize = u32(at + 18).toInt()
            val nameLen = u16(at + 26)
            val extraLen = u16(at + 28)
            val name = String(zip, at + 30, nameLen, Charsets.UTF_8)
            val recordLen = 30 + nameLen + extraLen + compSize
            if (name == entryName) dup = zip.copyOfRange(at, at + recordLen)
            at += recordLen
        }
        require(dup != null) { "entry $entryName not found" }
        val out = ByteArray(zip.size + dup.size)
        System.arraycopy(zip, 0, out, 0, cdAt)
        System.arraycopy(dup, 0, out, cdAt, dup.size)
        System.arraycopy(zip, cdAt, out, cdAt + dup.size, zip.size - cdAt)
        return out
    }

    fun parseOk(zip: ByteArray, sha: String = SHA): NytPolicy =
        NytRuleParser.parseZip(zip, sha, NytPolicy.OFFICIAL_UPDATE_URL)

    fun parseFailure(zip: ByteArray): NytPolicyException {
        try {
            parseOk(zip)
        } catch (e: NytPolicyException) {
            return e
        }
        fail("expected NytPolicyException")
        throw AssertionError("unreachable")
    }

    fun bundledJson(version: String = "1.0", userAgent: String = UA): String =
        NytPolicyJson.encode(
            NytPolicy(userAgent, true, BLOCK, version, "", NytPolicy.OFFICIAL_UPDATE_URL),
        )

    fun policyJson(policy: NytPolicy): String = NytPolicyJson.encode(policy)
}
