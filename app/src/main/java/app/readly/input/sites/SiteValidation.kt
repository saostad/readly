package app.readly.input.sites

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import org.jsoup.nodes.Element
import org.jsoup.select.Selector

/**
 * Strict domain validation shared by the importer, the cache codec, and
 * the matcher. Rule domains from upstream or cache are held to the
 * strictest bar (ASCII labels, no credentials/schemes/ports/wildcards,
 * never an IP literal or public-suffix-like stub); lookup hosts get a
 * slightly more lenient normalization that still rejects spoofs,
 * IP literals, and malformed input.
 */
object SiteDomains {
    /**
     * Minimal well-known two-label public suffixes. A rule for one of these
     * would match an entire registry subtree, so it is rejected as
     * public-suffix-like. This is a deliberate guard, not a public-suffix
     * list: single-label names are already rejected by the two-label rule.
     */
    private val MINIMAL_PUBLIC_SUFFIXES: Set<String> = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk",
        "co.jp", "ne.jp", "or.jp",
        "com.au", "net.au", "org.au",
        "co.nz", "com.br", "co.in", "co.za", "com.mx",
    )

    /**
     * Lowercase-normalized [raw] when it is acceptable as a rule match or
     * excluded domain, null otherwise. See [invalidReason] for why.
     */
    fun normalizeRuleDomain(raw: String): String? =
        if (invalidReason(raw) == null) raw.lowercase(Locale.US) else null

    /**
     * Why [raw] is rejected as a rule domain, or null when it is valid:
     * credentials/schemes/ports/paths, wildcards, IP literals,
     * public-suffix-like stubs, bad labels, or overlong input.
     */
    fun invalidReason(raw: String): String? {
        if (raw.isEmpty()) return "domain is empty"
        if (raw.length > 253) return "domain is over 253 chars"
        if (raw.any { it.isWhitespace() }) return "domain contains whitespace"
        if ("://" in raw || '@' in raw || ':' in raw || '/' in raw || '?' in raw || '#' in raw) {
            return "domain has credentials, scheme, port, or path"
        }
        if ('*' in raw) return "domain is a wildcard"
        if ('_' in raw) return "domain has an invalid label"
        if (raw.endsWith('.')) return "domain has a trailing dot"
        val labels = raw.lowercase(Locale.US).split('.')
        if (labels.size < 2) return "domain is public-suffix-like (needs two labels)"
        for (label in labels) {
            if (label.isEmpty() || label.length > 63) return "domain has an invalid label"
            if (label.startsWith('-') || label.endsWith('-')) return "domain has an invalid label"
            if (!label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) {
                return "domain has an invalid label"
            }
        }
        if (labels.last().all { it.isDigit() }) return "domain is an IP literal"
        val lower = raw.lowercase(Locale.US)
        if (lower in MINIMAL_PUBLIC_SUFFIXES) return "domain is public-suffix-like"
        return null
    }

    /**
     * Normalizes a lookup host for matching: trims, lowercases, tolerates
     * one trailing FQDN dot. Returns null for blank, malformed, spoof-shaped
     * (whitespace, userinfo, ports, wildcards), or IP-literal hosts, which
     * never match and keep existing ordinary reading.
     */
    fun normalizeLookupHost(host: String?): String? {
        if (host.isNullOrBlank()) return null
        var h = host.trim().lowercase(Locale.US)
        if (h.endsWith('.')) h = h.dropLast(1)
        if (h.isEmpty() || h.length > 253) return null
        if (h.any { it.isWhitespace() }) return null
        if ('@' in h || ':' in h || '/' in h || '?' in h || '#' in h || '*' in h) return null
        if ('_' in h) return null
        val labels = h.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 }) return null
        if (labels.last().all { it.isDigit() }) return null
        return h
    }

    /**
     * Parent domain for most-specific matching (`a.b.c` → `b.c`), or null
     * when fewer than two labels would remain (rule domains always have
     * at least two, so single-label candidates can never hit).
     */
    fun parentDomain(domain: String): String? {
        val dot = domain.indexOf('.')
        if (dot < 0) return null
        val parent = domain.substring(dot + 1)
        if ('.' !in parent) return null
        return parent
    }
}

/**
 * Request-URL eligibility for rule application. Rules apply only to plain
 * HTTPS fetches: `https` scheme, default port (or explicit 443), no
 * userinfo, and a matchable host. This is a pure data-level predicate for
 * Round 2 wiring; Round 1 applies no network behavior.
 */
object SiteRequests {
    /** True when a rule may be applied to a fetch of [url]. */
    fun isEligibleUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return false
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.userInfo != null) return false
        val port = uri.port
        if (port != -1 && port != 443) return false
        val host = uri.host ?: return false
        if (SiteDomains.normalizeLookupHost(host) == null) return false
        return true
    }

    /**
     * Why [raw] is rejected as a `referer_custom` value, or null when it is
     * a safe absolute HTTPS URL (no userinfo, default/443 port, matchable
     * host, no fragment, within bounds, transmittable as a header value).
     */
    fun invalidRefererReason(raw: String, maxLength: Int): String? {
        if (raw.isEmpty()) return "referer is empty"
        if (raw.length > maxLength) return "referer is over $maxLength chars"
        if (raw.any { it == '\n' || it == '\r' }) return "referer must be a single line"
        val bad = SiteHeaders.findInvalidChar(raw)
        if (bad != null) return "referer has invalid header character ${SiteHeaders.describeChar(bad)}"
        val uri = try {
            URI(raw)
        } catch (e: URISyntaxException) {
            return "referer is not a valid URL (${e.message})"
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return "referer must be an https URL"
        if (uri.userInfo != null) return "referer has userinfo"
        val port = uri.port
        if (port != -1 && port != 443) return "referer must use the default port"
        val host = uri.host ?: return "referer has no host"
        if (SiteDomains.normalizeLookupHost(host) == null) return "referer host is invalid"
        if (uri.fragment != null) return "referer must not have a fragment"
        return null
    }
}

/**
 * CSS selector and cleanup-name validation. Selectors are checked with the
 * project's existing Jsoup: Round 2 applies cleanup through Jsoup, so a
 * selector Jsoup cannot parse is honestly reported instead of silently
 * kept. Attribute and class names use a conservative token shape.
 */
object SiteSelectors {
    /**
     * Why [selector] is rejected, or null when Jsoup parses it and it is
     * within bounds.
     */
    fun invalidSelectorReason(selector: String, maxLength: Int): String? {
        if (selector.isBlank()) return "selector is blank"
        if (selector.length > maxLength) return "selector is over $maxLength chars"
        if (selector.any { it.code < 0x20 || it.code == 0x7F }) {
            return "selector has control characters"
        }
        try {
            Selector.select(selector, Element("div"))
        } catch (e: Selector.SelectorParseException) {
            return "selector is not valid (${e.message})"
        }
        return null
    }

    private val ATTR_RE = Regex("[A-Za-z_:][A-Za-z0-9_:.\\-]*")

    /** Conservative attribute-name shape within the length bound. */
    fun isValidAttrName(name: String): Boolean =
        name.length in 1..SiteCatalogLimits.MAX_CLEANUP_NAME_LENGTH && ATTR_RE.matches(name)

    /** Conservative class-name token: no whitespace/controls/separators, within bounds. */
    fun isValidClassName(name: String): Boolean {
        if (name.length !in 1..SiteCatalogLimits.MAX_CLEANUP_NAME_LENGTH) return false
        if (name.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }) return false
        if (name.any { it == ',' || it == '|' || it == '"' || it == '\'' }) return false
        return true
    }
}

/**
 * Explicit compatibility policy: declarative host-scope overrides applied
 * as data by the importer, not Kotlin branches per site.
 *
 * `nytimes.com` keeps the exact owner-tested boundary (the NYT rule
 * applies to `nytimes.com` and `www.nytimes.com` only, never to
 * `cooking.` or other subdomains). The general engine defaults to
 * subtree matching; this table grandfathers the stricter site without
 * broadening it.
 */
object SiteCompatPolicy {
    val HOST_SCOPE_OVERRIDES: Map<String, HostScope> = mapOf(
        "nytimes.com" to HostScope.APEX_AND_WWW,
    )

    /**
     * Scope for a rule covering [matchDomains]: the most restrictive
     * override across its domains, else [HostScope.SUBTREE].
     */
    fun scopeFor(matchDomains: Set<String>): HostScope {
        for (domain in matchDomains) {
            if (HOST_SCOPE_OVERRIDES[domain] == HostScope.APEX_AND_WWW) return HostScope.APEX_AND_WWW
        }
        return HostScope.SUBTREE
    }
}
