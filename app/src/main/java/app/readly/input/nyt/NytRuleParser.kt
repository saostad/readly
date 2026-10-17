package app.readly.input.nyt

import app.readly.input.sites.JsDataException
import app.readly.input.sites.JsDataUnsupportedException
import app.readly.input.sites.JsLiteral
import app.readly.input.sites.UpdateArchive

/**
 * Bounded data-only parser for the official Bypass Paywalls Clean update ZIP.
 *
 * Archive handling, manifest checks, and the `sites.js` micro-lexer live in
 * the shared [UpdateArchive]/[JsLiteral] core; this object keeps only the
 * single-site NYT entry lookup on top. The archive is read with the JDK ZIP
 * API as data: entry contents are streamed with manual byte counting
 * (declared sizes are never trusted), duplicate or missing required entries
 * are rejected, and nothing is ever extracted to disk. Only `sites.js` and
 * its sibling `manifest.json` are used; every other entry is drained within
 * the shared budget and ignored.
 *
 * `sites.js` is parsed with a small iterative lexer: the declared
 * `defaultSites` object is located outside strings/comments/regex literals,
 * then the unique direct "The New York Times" property is found with
 * balanced-nesting tracking, and its object body is parsed strictly. Nested
 * or unrelated mentions never match, and a property-access/operator suffix
 * after the entry is rejected. There is no `eval`, no JavaScript engine,
 * and no regex spanning unrelated objects. Only the actually observed
 * static fields are supported; anything else (unknown fields, dynamic
 * expressions, nested values) is an explicit [NytIncompatibleException],
 * never silently adopted.
 */
object NytRuleParser {
    /** Largest accepted update download (the inspected ZIP is ~0.3 MiB). */
    const val MAX_COMPRESSED_BYTES = 8L * 1024 * 1024

    /** Largest total uncompressed bytes scanned across all archive entries. */
    const val MAX_TOTAL_UNCOMPRESSED_BYTES = 32L * 1024 * 1024

    /** Largest accepted `sites.js` (the inspected one is ~0.1 MiB). */
    const val MAX_SITES_JS_BYTES = 2L * 1024 * 1024

    /** Largest accepted `manifest.json` (the inspected one is ~31 KiB). */
    const val MAX_MANIFEST_BYTES = 64L * 1024

    /** Largest accepted number of archive entries (the inspected ZIP has 65). */
    const val MAX_ENTRIES = 256

    private const val LABEL = "NYT rule update"

    private data class NytEntry(
        val domain: String,
        val allowCookies: Boolean,
        val blockPattern: String?,
        val userAgent: String,
    )

    @Throws(NytPolicyException::class)
    fun parseZip(zipBytes: ByteArray, sourceSha256: String, sourceUrl: String): NytPolicy {
        return mapErrors {
            val archive = UpdateArchive.readArchive(zipBytes, LABEL)
            val version = UpdateArchive.parseManifestVersion(archive.manifestJson, LABEL)
            val entry = parseNytEntry(archive.sitesJs)
            NytPolicy(
                userAgent = entry.userAgent,
                allowCookies = entry.allowCookies,
                blockPattern = entry.blockPattern,
                sourceVersion = version,
                sourceSha256 = sourceSha256,
                sourceUrl = sourceUrl,
            )
        }
    }

    /**
     * Maps shared-core failures to the NYT error types without changing the
     * message text: malformed input is [NytPolicyException], valid but
     * unsupported input is [NytIncompatibleException].
     */
    private fun <T> mapErrors(block: () -> T): T {
        try {
            return block()
        } catch (e: JsDataUnsupportedException) {
            throw NytIncompatibleException(e.message ?: "$LABEL is not supported", e)
        } catch (e: JsDataException) {
            throw NytPolicyException(e.message ?: "$LABEL failed", e)
        }
    }

    // ------------------------------------------------------------------
    // sites.js: locate the NYT entry
    // ------------------------------------------------------------------

    private fun parseNytEntry(sitesJs: String): NytEntry {
        val key = NytPolicy.SITE_KEY
        val roots = JsLiteral.findDefaultSitesRoots(sitesJs, LABEL)
        if (roots.isEmpty()) {
            throw NytIncompatibleException(
                "$LABEL \"sites.js\" has no \"defaultSites\" object",
            )
        }
        if (roots.size > 1) {
            throw NytIncompatibleException(
                "$LABEL \"sites.js\" has multiple \"defaultSites\" declarations: " +
                    "ambiguous, refusing to guess",
            )
        }
        val root = roots.single()
        val positions = JsLiteral.scanDirectEntries(sitesJs, root.open, root.close, LABEL)
            .filter { it.key == key }
            .map { it.valuePos }
        if (positions.isEmpty()) {
            throw NytIncompatibleException(
                "$LABEL \"sites.js\" has no \"$key\" entry in \"defaultSites\"",
            )
        }
        if (positions.size > 1) {
            throw NytIncompatibleException(
                "$LABEL \"sites.js\" has duplicate \"$key\" entries in \"defaultSites\"",
            )
        }
        val (entry, end) = parseEntryObject(sitesJs, positions.single())
        val j = JsLiteral.skipWsAndComments(sitesJs, end, LABEL)
        if (j >= sitesJs.length) {
            throw NytPolicyException("$LABEL \"$key\" entry is unterminated")
        }
        val next = sitesJs[j]
        if (next != ',' && next != '}') {
            throw NytIncompatibleException(
                "$LABEL \"$key\" entry has unsupported suffix " +
                    "\"$next\": expected ',' or '}' after the entry object",
            )
        }
        return entry
    }

    // ------------------------------------------------------------------
    // sites.js: strict entry-object parse
    // ------------------------------------------------------------------

    private val KNOWN_FIELDS = setOf("domain", "allow_cookies", "block_regex", "useragent_custom")

    private fun parseEntryObject(s: String, afterColon: Int): Pair<NytEntry, Int> {
        val key = NytPolicy.SITE_KEY
        var i = JsLiteral.skipWsAndComments(s, afterColon, LABEL)
        if (i >= s.length || s[i] != '{') {
            throw NytIncompatibleException(
                "$LABEL \"$key\" entry is not a static object: " +
                    "unsupported dynamic expression",
            )
        }
        i++
        var domain: String? = null
        var allowCookies: Boolean? = null
        var blockPattern: String? = null
        var userAgent: String? = null
        while (true) {
            i = JsLiteral.skipWsAndComments(s, i, LABEL)
            if (i >= s.length) {
                throw NytPolicyException("$LABEL \"$key\" object is unterminated")
            }
            if (s[i] == '}') break
            val field: String
            when {
                s[i] == '"' || s[i] == '\'' -> {
                    val (v, e) = JsLiteral.readString(s, i, LABEL)
                    field = v
                    i = e
                }
                s[i].isLetter() || s[i] == '_' || s[i] == '$' -> {
                    val start = i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '$')) {
                        i++
                    }
                    field = s.substring(start, i)
                }
                else -> throw NytPolicyException(
                    "$LABEL \"$key\" object has unexpected character '${s[i]}'",
                )
            }
            if (field !in KNOWN_FIELDS) {
                throw NytIncompatibleException(
                    "$LABEL \"$key\" entry has unsupported field \"$field\": " +
                        "only ${KNOWN_FIELDS.sorted().joinToString(", ") { "\"$it\"" }} are supported",
                )
            }
            i = JsLiteral.skipWsAndComments(s, i, LABEL)
            if (i >= s.length || s[i] != ':') {
                throw NytPolicyException(
                    "$LABEL \"$key\" entry: expected ':' after field \"$field\"",
                )
            }
            i = JsLiteral.skipWsAndComments(s, i + 1, LABEL)
            if (i >= s.length) {
                throw NytPolicyException("$LABEL \"$key\" object is unterminated")
            }
            when {
                s[i] == '"' || s[i] == '\'' -> {
                    val (v, e) = JsLiteral.readString(s, i, LABEL)
                    i = e
                    when (field) {
                        "domain" -> {
                            if (domain != null) duplicateField(key, field)
                            domain = v
                        }
                        "useragent_custom" -> {
                            if (userAgent != null) duplicateField(key, field)
                            userAgent = v
                        }
                        "allow_cookies" -> wrongType(key, field, "a number")
                        "block_regex" -> wrongType(key, field, "a regex literal")
                    }
                }
                s[i] == '/' && !JsLiteral.isCommentStart(s, i) -> {
                    val (source, e) = JsLiteral.readRegex(s, i, LABEL)
                    i = e
                    if (field != "block_regex") wrongType(key, field, typeOf(field))
                    if (blockPattern != null) duplicateField(key, field)
                    if (source.length > NytPolicy.BLOCK_PATTERN_MAX_LENGTH) {
                        throw NytIncompatibleException(
                            "$LABEL \"$key\" entry: \"block_regex\" pattern is too large " +
                                "(${source.length} chars)",
                        )
                    }
                    blockPattern = source
                }
                s[i] == '-' || s[i].isDigit() -> {
                    if (s[i] == '-' && (i + 1 >= s.length || !s[i + 1].isDigit())) {
                        throw NytIncompatibleException(
                            "$LABEL \"$key\" entry: field \"$field\" has " +
                                "unsupported dynamic expression",
                        )
                    }
                    val (num, e) = JsLiteral.readNumber(s, i)
                    i = e
                    if (field != "allow_cookies") wrongType(key, field, typeOf(field))
                    if (allowCookies != null) duplicateField(key, field)
                    if (!num.matches(Regex("-?\\d+"))) {
                        throw NytIncompatibleException(
                            "$LABEL \"$key\" entry: field \"allow_cookies\" has " +
                                "unsupported numeric value \"$num\"",
                        )
                    }
                    val value = num.toLongOrNull()
                    if (value != 0L && value != 1L) {
                        throw NytIncompatibleException(
                            "$LABEL \"$key\" entry: field \"allow_cookies\" has " +
                                "unsupported value \"$num\": expected 0 or 1",
                        )
                    }
                    allowCookies = value == 1L
                }
                s[i] == '{' || s[i] == '[' -> throw NytIncompatibleException(
                    "$LABEL \"$key\" entry: field \"$field\" has unsupported nested value",
                )
                s[i] == '`' -> throw NytIncompatibleException(
                    "$LABEL \"$key\" entry: field \"$field\" uses " +
                        "unsupported template literal",
                )
                s[i].isLetter() || s[i] == '_' || s[i] == '$' -> {
                    val start = i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '$')) {
                        i++
                    }
                    throw NytIncompatibleException(
                        "$LABEL \"$key\" entry: field \"$field\" has " +
                            "unsupported dynamic value \"${s.substring(start, i)}\"",
                    )
                }
                else -> {
                    if (s[i] in "+*/%<>&|!?^~=(.") {
                        throw NytIncompatibleException(
                            "$LABEL \"$key\" entry: field \"$field\" has " +
                                "unsupported dynamic expression",
                        )
                    }
                    throw NytPolicyException(
                        "$LABEL \"$key\" entry: unexpected character '${s[i]}' " +
                            "for field \"$field\"",
                    )
                }
            }
            i = JsLiteral.skipWsAndComments(s, i, LABEL)
            if (i >= s.length) {
                throw NytPolicyException("$LABEL \"$key\" object is unterminated")
            }
            if (s[i] == ',') {
                i++
                continue
            }
            if (s[i] == '}') break
            if (s[i] in "+-*/%<>&|!?^~=(.") {
                throw NytIncompatibleException(
                    "$LABEL \"$key\" entry: field \"$field\" has " +
                        "unsupported dynamic expression",
                )
            }
            throw NytPolicyException(
                "$LABEL \"$key\" entry: expected ',' or '}' after field \"$field\"",
            )
        }
        val d = domain ?: throw NytIncompatibleException(
            "$LABEL \"$key\" entry is missing required field \"domain\"",
        )
        val u = userAgent ?: throw NytIncompatibleException(
            "$LABEL \"$key\" entry is missing required field \"useragent_custom\"",
        )
        if (d != NytPolicy.DOMAIN) {
            throw NytIncompatibleException(
                "$LABEL \"$key\" entry has unexpected domain \"$d\": " +
                    "expected \"${NytPolicy.DOMAIN}\"",
            )
        }
        if (u.isBlank()) {
            throw NytIncompatibleException(
                "$LABEL \"$key\" entry: \"useragent_custom\" is blank",
            )
        }
        if (u.any { it == '\n' || it == '\r' }) {
            throw NytIncompatibleException(
                "$LABEL \"$key\" entry: \"useragent_custom\" must be a single line",
            )
        }
        val badHeader = NytPolicy.findInvalidHeaderChar(u)
        if (badHeader != null) {
            throw NytIncompatibleException(
                "$LABEL \"$key\" entry: \"useragent_custom\" contains invalid " +
                    "HTTP header character ${NytPolicy.describeHeaderChar(badHeader)}",
            )
        }
        if (u.length > NytPolicy.USER_AGENT_MAX_LENGTH) {
            throw NytIncompatibleException(
                "$LABEL \"$key\" entry: \"useragent_custom\" is too long " +
                    "(${u.length} chars, max ${NytPolicy.USER_AGENT_MAX_LENGTH})",
            )
        }
        // Browser-only directives default when upstream drops them; the no-JS
        // fetcher never runs them either way.
        return NytEntry(d, allowCookies ?: false, blockPattern, u) to i + 1
    }

    private fun typeOf(field: String): String = when (field) {
        "domain", "useragent_custom" -> "a string"
        "allow_cookies" -> "a number"
        else -> "a regex literal"
    }

    private fun wrongType(key: String, field: String, expected: String): Nothing {
        throw NytIncompatibleException(
            "$LABEL \"$key\" entry: field \"$field\" must be $expected",
        )
    }

    private fun duplicateField(key: String, field: String): Nothing {
        throw NytIncompatibleException(
            "$LABEL \"$key\" entry has duplicate field \"$field\"",
        )
    }
}
