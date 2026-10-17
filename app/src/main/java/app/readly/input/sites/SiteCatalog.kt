package app.readly.input.sites

/**
 * Immutable data-driven catalog of website rules imported from the
 * official Bypass Paywalls Clean `sites.js`.
 *
 * A catalog carries per-domain request policies (User-Agent, Referer,
 * literal extra headers) and declarative HTML directives (element cleanup
 * selectors, plus typed-but-unapplied LD+JSON/AMP selectors), with honest
 * per-rule notes wherever upstream relies on browser-only behavior. It is
 * a subset of the extension's effective runtime: importing a rule never
 * claims the site reads successfully.
 *
 * Matching is most-specific-wins over exact label boundaries: host
 * `a.example.com` matches rule domain `example.com` but spoof
 * `evil-example.com` and `evil example.com` never match. Rules apply to
 * subdomains ([HostScope.SUBTREE]) unless an explicit compatibility entry
 * restricts them ([HostScope.APEX_AND_WWW]).
 */
data class SiteCatalog(
    val meta: SiteCatalogMeta,
    /** Imported rules in deterministic order (upstream source order). */
    val rules: List<SiteRule>,
    /** Entries considered but not imported as rules, with reasons. */
    val skipped: List<SkippedSite>,
    /**
     * Explicitly withheld match domains: no less-specific rule may apply
     * here. A more-specific valid rule still wins normally (it matches
     * before the walk reaches a withheld level). Persisted in the codec.
     */
    val withheldDomains: Set<String> = emptySet(),
) {
    /** Effective match domain to rule, built once; conflicts are withheld at import. */
    private val index: Map<String, SiteRule> by lazy {
        LinkedHashMap<String, SiteRule>().also { map ->
            for (rule in rules) {
                for (domain in rule.matchDomains) {
                    map.putIfAbsent(domain, rule)
                }
            }
        }
    }

    /**
     * The rule for [host], most-specific match wins, or null when no rule
     * matches (unknown hosts keep existing ordinary reading). Excluded and
     * out-of-scope matches fall through to a less-specific rule.
     */
    fun findRule(host: String?): SiteRule? = match(host)?.rule

    companion object {
        /** The only URL production catalog updates may download from. */
        const val OFFICIAL_UPDATE_URL =
            "https://gitflic.ru/project/magnolia1234/bpc_uploads/blob/raw" +
                "?file=bypass-paywalls-chrome-clean-master.zip"
    }

    /**
     * Like [findRule] but also reports which effective match domain won.
     * Null host, blank host, IP literals, and malformed hosts never match.
     */
    fun match(host: String?): SiteMatch? {
        val normalized = SiteDomains.normalizeLookupHost(host) ?: return null
        var candidate: String? = normalized
        while (candidate != null) {
            // A withheld level stops fallback: no broader rule may reapply
            // where an exception, conflict, or restriction was dropped.
            // Fail closed even if a tampered cache also claims the domain.
            if (candidate in withheldDomains) return null
            val rule = index[candidate]
            if (rule != null && !rule.isExcluded(normalized) && rule.inScope(normalized, candidate)) {
                return SiteMatch(rule, candidate)
            }
            candidate = SiteDomains.parentDomain(candidate)
        }
        return null
    }
}

/** A [SiteCatalog.match] hit: the winning rule and its effective match domain. */
data class SiteMatch(val rule: SiteRule, val matchedDomain: String)

/** Provenance for a catalog: upstream version, source bytes, and license. */
data class SiteCatalogMeta(
    /** Upstream extension version, e.g. `4.4.7.4`. */
    val version: String,
    /** Lowercase hex SHA-256 of the update ZIP, or "" when unknown. */
    val sourceSha256: String,
    /** URL the update ZIP came from (normally the official update URL). */
    val sourceUrl: String,
    /** Short attribution naming the upstream project and MIT license. */
    val attribution: String,
    /** Full upstream license text shipped with the data. */
    val license: String,
)

/** One upstream site entry that was considered but not imported as a rule. */
data class SkippedSite(
    /** Upstream `defaultSites` key, or a `(catalog …)` marker. */
    val siteKey: String,
    /** Why the entry is not a rule (pseudo marker, infra, unsupported, …). */
    val reason: String,
)

/**
 * One imported site rule: the effective match domains plus the native
 * request/HTML directives and honesty notes. Directives are data only in
 * Round 1; no network or HTML behavior is wired to them yet.
 */
data class SiteRule(
    /** Upstream `defaultSites` key (exceptions append `" › exception N"`). */
    val siteKey: String,
    /** Effective match domains after group expansion and exclusions. */
    val matchDomains: Set<String>,
    /** Hosts (and their subtrees) this rule must not apply to. */
    val excludedDomains: Set<String>,
    val scope: HostScope,
    val request: SiteRequestPolicy,
    val html: SiteHtmlDirectives,
    val status: RuleStatus,
    /** Bounded limitation/provenance notes (browser-only steps, withholds, …). */
    val notes: List<String>,
) {
    /** True when [host] (already normalized) is excluded from this rule. */
    fun isExcluded(host: String): Boolean =
        excludedDomains.any { host == it || host.endsWith(".$it") }

    /** True when [host] is in scope for a hit on effective domain [matched]. */
    fun inScope(host: String, matched: String): Boolean = when (scope) {
        HostScope.SUBTREE -> true
        HostScope.APEX_AND_WWW -> host == matched || host == "www.$matched"
    }

    /** True when the rule carries at least one applied native directive. */
    fun hasAppliedDirectives(): Boolean = request.hasEffect() || html.hasAppliedEffect()
}

/** Which hosts under a match domain a rule applies to. */
enum class HostScope {
    /** The match domain and all its subdomains. */
    SUBTREE,

    /** Only the match domain itself and its single `www.` host. */
    APEX_AND_WWW,
}

/** How completely the native engine covers one imported entry. */
enum class RuleStatus {
    /**
     * Every recognized field is a natively applied directive; nothing
     * browser-only, future, unsupported, or withheld.
     */
    FULL,

    /** Imported with honest limitations recorded in [SiteRule.notes]. */
    PARTIAL,
}

/**
 * Resolved concrete request headers for one rule. Sources are recorded so
 * a cached catalog shows where each header came from without re-parsing.
 */
data class SiteRequestPolicy(
    /** Concrete User-Agent value, or null when the rule sets none. */
    val userAgent: String?,
    /** `"googlebot"`, `"bingbot"`, `"facebookbot"`, or `"custom"`. */
    val userAgentSource: String?,
    /** Concrete Referer value, or null when the rule sets none. */
    val referer: String?,
    /** `"google"`, `"facebook"`, `"twitter"`, `"custom"`, or `"googlebot"`. */
    val refererSource: String?,
    /** Validated literal `headers_custom` entries (upstream order). */
    val extraHeaders: Map<String, String>,
) {
    fun hasEffect(): Boolean = userAgent != null || referer != null || extraHeaders.isNotEmpty()

    /** Concrete headers to send: User-Agent, Referer, then extras. */
    fun asHeaders(): Map<String, String> {
        if (!hasEffect()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        if (userAgent != null) out["User-Agent"] = userAgent
        if (referer != null) out["Referer"] = referer
        out.putAll(extraHeaders)
        return out
    }
}

/** Declarative HTML directives: applied cleanup plus typed future selectors. */
data class SiteHtmlDirectives(
    /** Declarative element cleanup ops (data only in Round 1). */
    val cleanup: List<CleanupOp>,
    /** Typed LD+JSON selector rule, or null; never applied in Round 1. */
    val ldJson: LdJsonRule?,
    /** Typed AMP selector rule, or null; never applied in Round 1. */
    val ampRedirect: AmpRedirectRule?,
) {
    /** Applied directives only; LD+JSON/AMP are typed but unapplied. */
    fun hasAppliedEffect(): Boolean = cleanup.isNotEmpty()

    fun hasEffect(): Boolean = hasAppliedEffect() || ldJson != null || ampRedirect != null
}

/**
 * One declarative cleanup operation (mirrors the extension's `cs_code`).
 * One upstream `cond` object with several actions becomes several sibling
 * ops sharing that selector AND one [groupId], in extension order (remove,
 * classes, attributes). The executor snapshots the matched nodes once per
 * group and applies every sibling action to that original set: re-selecting
 * after an earlier sibling mutated the DOM would miss nodes (e.g. a class
 * removal can make the shared selector stop matching). Siblinghood is
 * explicit via [groupId], never guessed from adjacent selectors.
 */
data class CleanupOp(
    val kind: CleanupKind,
    /** CSS selector: hidden nodes ([CleanupKind.HIDE]) or match context (other kinds). */
    val selector: String,
    /** Attribute names ([CleanupKind.REMOVE_ATTRS]) or class names ([CleanupKind.REMOVE_CLASSES]). */
    val names: List<String>,
    /**
     * Explicit sibling group: every op from one upstream `cond` (or one
     * `hide_elem`) shares one id, unique within the rule. Groups are never
     * merged heuristically; unrelated consecutive selectors always differ.
     */
    val groupId: Int,
    /**
     * Nested `elems` ops, bounded by [SiteCatalogLimits.MAX_CLEANUP_DEPTH].
     * Global once, never scoped: the extension runs these with document
     * selectors a single time when the parent `cond` matches at least one
     * node (`if (first && elem.elems)`), so the executor does the same
     * rather than re-scoping them under each matched node. Removal still
     * counts as a parent match for this purpose.
     */
    val children: List<CleanupOp>,
)

/** Supported cleanup operation kinds; anything else stays a diagnostic note. */
enum class CleanupKind {
    /** Hide every node matching [CleanupOp.selector]. */
    HIDE,

    /** Remove every node matching [CleanupOp.selector]. */
    REMOVE,

    /** Strip [CleanupOp.names] attributes from nodes matching the selector. */
    REMOVE_ATTRS,

    /** Strip [CleanupOp.names] classes from nodes matching the selector. */
    REMOVE_CLASSES,
}

/**
 * Typed LD+JSON selector rule (`ld_json`/`ld_json_url`:
 * `paywallSelector|articleSelector[|extra...]`). Recorded for future use;
 * never applied in Round 1.
 */
data class LdJsonRule(
    /** Which upstream field this came from: `"ld_json"` or `"ld_json_url"`. */
    val kind: String,
    val paywallSelector: String,
    val articleSelector: String,
    val extra: List<String>,
)

/**
 * Typed AMP selector rule (`amp_redirect`: `paywallSelector[|ampUrl]`).
 * Recorded for future use; never applied in Round 1.
 */
data class AmpRedirectRule(
    val paywallSelector: String,
    /** Optional `{path}`/`{host}` URL template, or null. */
    val ampUrlTemplate: String?,
)

/** Size bounds shared by the importer and the cache codec. */
object SiteCatalogLimits {
    /** Most rules kept in one catalog. */
    const val MAX_RULES = 4096

    /** Most effective match domains kept per rule. */
    const val MAX_MATCH_DOMAINS_PER_RULE = 512

    /** Most effective match domains kept across the catalog. */
    const val MAX_TOTAL_MATCH_DOMAINS = 16384

    /** Most excluded domains kept per rule. */
    const val MAX_EXCLUDED_PER_RULE = 256

    /** Most withheld barrier domains kept per catalog. */
    const val MAX_WITHHELD_DOMAINS = 4096

    /** Most literal extra headers kept per rule. */
    const val MAX_EXTRA_HEADERS = 32

    /** Most cleanup ops kept per rule (children included). */
    const val MAX_CLEANUP_OPS = 64

    /** Deepest accepted cleanup `elems` nesting. */
    const val MAX_CLEANUP_DEPTH = 4

    /** Longest accepted CSS selector. */
    const val MAX_SELECTOR_LENGTH = 1024

    /** Longest accepted attribute/class name in cleanup ops. */
    const val MAX_CLEANUP_NAME_LENGTH = 128

    /** Most attribute/class names accepted per cleanup op. */
    const val MAX_CLEANUP_NAMES = 32

    /** Most LD+JSON extra segments accepted. */
    const val MAX_LD_JSON_EXTRA = 4

    /** Longest accepted LD+JSON extra segment. */
    const val MAX_LD_JSON_EXTRA_LENGTH = 256

    /** Longest accepted AMP URL template. */
    const val MAX_AMP_URL_LENGTH = 512

    /** Most honesty notes kept per rule. */
    const val MAX_NOTES_PER_RULE = 16

    /** Longest accepted note/reason text. */
    const val MAX_NOTE_LENGTH = 320

    /** Most skipped-entry records kept. */
    const val MAX_SKIPPED_RECORDS = 2048

    /** Longest accepted upstream site key. */
    const val MAX_SITE_KEY_LENGTH = 200

    /** Longest accepted attribution line. */
    const val MAX_ATTRIBUTION_LENGTH = 2048

    /** Longest accepted attribution/license text. */
    const val MAX_LICENSE_LENGTH = 65536
}

/**
 * Known bot User-Agents from the extension's `background.js` (desktop
 * strings; the native fetcher has no mobile/desktop switch, so the
 * desktop constants are used and the choice is documented, not guessed
 * per request).
 */
enum class KnownBot(
    val id: String,
    val userAgent: String,
    /** Referer the extension sets alongside this bot, if any. */
    val referer: String?,
) {
    GOOGLEBOT(
        "googlebot",
        "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
        "https://www.google.com/",
    ),
    BINGBOT(
        "bingbot",
        "Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)",
        null,
    ),
    FACEBOOKBOT(
        "facebookbot",
        "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)",
        null,
    );

    companion object {
        fun byId(id: String): KnownBot? = entries.firstOrNull { it.id == id }
    }
}

/** Known Referer presets from the extension's `background.js`. */
enum class RefererPreset(val id: String, val url: String) {
    GOOGLE("google", "https://www.google.com/"),
    FACEBOOK("facebook", "https://www.facebook.com/"),
    TWITTER("twitter", "https://t.co/");

    companion object {
        fun byId(id: String): RefererPreset? = entries.firstOrNull { it.id == id }
    }
}

/** Any failure to import or decode a site catalog. The message names the operation. */
open class SiteCatalogException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The upstream data is well-formed but uses an unsupported shape (multiple
 * roots, unknown schema, bad version). Callers keep the last-working
 * catalog and surface this message.
 */
class SiteCatalogUnsupportedException(message: String, cause: Throwable? = null) :
    SiteCatalogException(message, cause)

/**
 * A catalog-wide safety bound (withheld barriers) would be exceeded. This
 * escapes per-site isolation: truncating restriction authority would leak
 * parent directives, so the whole candidate catalog is rejected and the
 * caller keeps the last-working catalog. Never recorded as a per-site skip.
 */
class SiteCatalogOverflowException(message: String) : SiteCatalogException(message)
