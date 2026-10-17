package app.readly.input.nyt

import java.util.Locale
import org.json.JSONException
import org.json.JSONObject

/**
 * Immutable declarative policy for reading nytimes.com shared links.
 *
 * This covers one upstream site entry only ("The New York Times" from Bypass
 * Paywalls Clean); it is not full extension coverage. [allowCookies] and
 * [blockPattern] are browser directives: our fetcher downloads HTML without
 * executing scripts, so they are recognized and reported but never run. Only
 * [userAgent] affects fetching (wired in Round 2).
 *
 * [sourceSha256] is the locally computed SHA-256 of the update ZIP the rule
 * came from ("" for the bundled policy, which predates ZIP-hash recording).
 * It is a local integrity note for reporting, not cryptographic publisher
 * verification.
 */
data class NytPolicy(
    val userAgent: String,
    val allowCookies: Boolean,
    /** Opaque `block_regex` source; stored for reporting, never compiled or executed. */
    val blockPattern: String?,
    /** Upstream extension version, e.g. `4.4.7.4`. */
    val sourceVersion: String,
    /** Lowercase hex SHA-256 of the update ZIP, or "" when unknown. */
    val sourceSha256: String,
    val sourceUrl: String,
) {
    companion object {
        const val DOMAIN = "nytimes.com"

        /** Hosts this policy applies to. Subdomains (e.g. `cooking.`) are not covered. */
        val SUPPORTED_HOSTS: Set<String> = setOf("nytimes.com", "www.nytimes.com")

        /** Upstream `sites.js` key this policy is parsed from. */
        const val SITE_KEY = "The New York Times"

        /** The only URL production updates may download from. */
        const val OFFICIAL_UPDATE_URL = app.readly.input.sites.SiteCatalog.OFFICIAL_UPDATE_URL

        /** Longest User-Agent we accept (the inspected one is ~50 chars). */
        const val USER_AGENT_MAX_LENGTH = 512

        /** Longest `block_regex` source we store. */
        const val BLOCK_PATTERN_MAX_LENGTH = 8192

        fun isSupportedHost(host: String?): Boolean =
            host?.lowercase(Locale.US) in SUPPORTED_HOSTS

        /** True for numeric dotted versions like `4.4.7.4` (1-8 parts, 1-6 digits each). */
        fun isValidVersion(version: String): Boolean =
            app.readly.input.sites.SiteVersions.isValidVersion(version)

        /**
         * Numeric dotted comparison (`4.10` > `4.9`; missing parts are 0, so
         * `4.4` == `4.4.0`). Total: all-digit parts compare by value at any
         * size, anything else compares lexically.
         */
        fun compareVersions(a: String, b: String): Int =
            app.readly.input.sites.SiteVersions.compareVersions(a, b)

        /**
         * First character that cannot appear in an HTTP header field value:
         * any C0/C1 control or DEL (including NUL, tab, newline) or anything
         * above Latin-1 that the transport cannot send. `' '` (0x20),
         * visible ASCII (0x21-0x7E) and obs-text (0x80-0xFF) are allowed.
         * Null when the value is transmittable.
         */
        fun findInvalidHeaderChar(value: String): Char? =
            app.readly.input.sites.SiteHeaders.findInvalidChar(value)

        /** `U+XXXX` rendering for header-validation errors (never the raw control). */
        fun describeHeaderChar(c: Char): String =
            app.readly.input.sites.SiteHeaders.describeChar(c)
    }

    /**
     * True when the fetched rule matches, ignoring provenance ([sourceSha256],
     * [sourceUrl]). A same-rule re-download still refreshes the cache so the
     * recorded hash describes verified bytes.
     */
    internal fun sameRule(other: NytPolicy): Boolean =
        userAgent == other.userAgent &&
            allowCookies == other.allowCookies &&
            blockPattern == other.blockPattern &&
            sourceVersion == other.sourceVersion
}

/** Any failure to parse, validate or load an NYT rule. The message names the operation. */
open class NytPolicyException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The upstream rule changed in a way we do not support (unexpected field,
 * dynamic expression, wrong domain, bad version, missing entry). Callers keep
 * the last-working policy and surface this message.
 */
class NytIncompatibleException(message: String, cause: Throwable? = null) : NytPolicyException(message, cause)

/**
 * JSON codec for [NytPolicy]. This is our own declarative rule format (used
 * for the bundled asset and the cache), never the upstream extension code.
 * Unknown fields are ignored so old readers tolerate new writers; every
 * required field is validated strictly.
 */
object NytPolicyJson {
    const val SCHEMA = 1

    private val SHA_RE = Regex("[0-9a-f]{64}")

    fun encode(policy: NytPolicy): String {
        val o = JSONObject()
        o.put("schema", SCHEMA)
        o.put("domain", NytPolicy.DOMAIN)
        o.put("userAgent", policy.userAgent)
        o.put("allowCookies", policy.allowCookies)
        if (policy.blockPattern == null) {
            o.put("blockPattern", JSONObject.NULL)
        } else {
            o.put("blockPattern", policy.blockPattern)
        }
        o.put("sourceVersion", policy.sourceVersion)
        o.put("sourceSha256", policy.sourceSha256)
        o.put("sourceUrl", policy.sourceUrl)
        return o.toString(2)
    }

    @Throws(NytPolicyException::class)
    fun decode(json: String, what: String = "NYT policy"): NytPolicy {
        val o = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw NytPolicyException(
                "$what JSON is invalid: ${e.javaClass.name}: ${e.message}",
                e,
            )
        }
        fun fail(detail: String): Nothing = throw NytPolicyException("$what JSON is invalid: $detail")
        requireSchema(o, what)
        val domain = o.opt("domain")
        if (domain !is String || domain != NytPolicy.DOMAIN) {
            fail("unexpected domain ${describeJsonValue(domain)}: expected \"${NytPolicy.DOMAIN}\"")
        }
        val userAgent = o.opt("userAgent")
        if (userAgent !is String) fail("\"userAgent\" must be a string")
        if (userAgent.isBlank()) fail("\"userAgent\" is blank")
        if (userAgent.any { it == '\n' || it == '\r' }) fail("\"userAgent\" must be a single line")
        val badHeader = NytPolicy.findInvalidHeaderChar(userAgent)
        if (badHeader != null) {
            fail(
                "\"userAgent\" contains invalid HTTP header character " +
                    "${NytPolicy.describeHeaderChar(badHeader)}",
            )
        }
        if (userAgent.length > NytPolicy.USER_AGENT_MAX_LENGTH) {
            fail("\"userAgent\" is too long (${userAgent.length} chars, max ${NytPolicy.USER_AGENT_MAX_LENGTH})")
        }
        val allowCookies = when (val v = o.opt("allowCookies")) {
            is Boolean -> v
            // Tolerate hand-written 0/1; anything else is corrupt.
            is Int, is Long -> {
                val n = (v as Number).toLong()
                if (n == 0L || n == 1L) {
                    n == 1L
                } else {
                    fail("\"allowCookies\" has unsupported value $v: expected true/false or 0/1")
                }
            }
            else -> fail("\"allowCookies\" must be true/false or 0/1")
        }
        val blockPattern = when (val v = o.opt("blockPattern")) {
            null, JSONObject.NULL -> null
            is String -> {
                if (v.isEmpty()) fail("\"blockPattern\" is empty")
                if (v.length > NytPolicy.BLOCK_PATTERN_MAX_LENGTH) {
                    fail("\"blockPattern\" is too large (${v.length} chars)")
                }
                v
            }
            else -> fail("\"blockPattern\" must be a string or null")
        }
        val version = o.opt("sourceVersion")
        if (version !is String || !NytPolicy.isValidVersion(version)) {
            fail("invalid \"sourceVersion\" ${describeJsonValue(version)}: expected a numeric dotted version")
        }
        val sha = o.opt("sourceSha256")
        if (sha !is String || (sha.isNotEmpty() && !SHA_RE.matches(sha))) {
            fail("invalid \"sourceSha256\": expected \"\" or 64 lowercase hex chars")
        }
        val url = o.opt("sourceUrl")
        if (url !is String || url != NytPolicy.OFFICIAL_UPDATE_URL) {
            fail("unexpected \"sourceUrl\" ${describeJsonValue(url)}: expected the official update URL")
        }
        return NytPolicy(
            userAgent = userAgent,
            allowCookies = allowCookies,
            blockPattern = blockPattern,
            sourceVersion = version,
            sourceSha256 = sha,
            sourceUrl = url,
        )
    }

    /** Strict `schema: 1` check shared by the policy and status codecs. */
    internal fun requireSchema(o: JSONObject, what: String) {
        val schema = o.opt("schema")
        val ok = (schema is Int || schema is Long) && schema.toLong() == SCHEMA.toLong()
        if (!ok) {
            throw NytPolicyException(
                "$what JSON is invalid: unsupported schema ${describeJsonValue(schema)}",
            )
        }
    }

    internal fun describeJsonValue(v: Any?): String =
        app.readly.input.sites.UpdateArchive.describeJsonValue(v)
}
