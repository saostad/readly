package app.readly.input.sites

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * General bounded data-only importer for the official update archive's
 * `defaultSites` object: every applicable static direct entry becomes an
 * immutable [SiteRule], without any per-site Kotlin branches.
 *
 * Only literal data is read (quoted/unquoted keys, strings with escapes,
 * numbers, booleans, null, bounded objects/arrays, opaque RegExp tokens).
 * There is no `eval`, no JavaScript engine, and no execution of the tail
 * code after `defaultSites`. A malformed enclosing structure, multiple
 * roots, or a ZIP error rejects the whole catalog; a structurally
 * delimited but unsupported site is skipped or marked partial with its
 * site key and reason, and never erases unrelated valid sites.
 *
 * Field taxonomy per entry:
 * - native request data: `useragent`, `useragent_custom`, `referer`,
 *   `referer_custom`, literal `headers_custom`;
 * - native declarative HTML data: supported `cs_code` primitives;
 * - typed-but-unapplied future data: `ld_json`, `ld_json_url`,
 *   `amp_redirect`;
 * - recognized browser-only limitations: cookies/subresource blocking,
 *   DOMPurify flags, local-storage clearing, external-link helpers
 *   (recorded, never applied as browser operations);
 * - `headers_filter`/`random_ip`: request modifiers the native engine
 *   cannot scope, so the affected request headers are withheld, never
 *   applied unrestricted;
 * - `block_regex_general`: infrastructure vendor patterns, never
 *   publisher rules;
 * - anything else: unknown, recorded as a limitation, never silently
 *   adopted.
 */
object SitesImporter {
    const val LABEL = "Site rule update"

    const val ATTRIBUTION =
        "Website rules transcribed from Bypass Paywalls Clean " +
            "(https://gitflic.ru/project/magnolia1234/bypass-paywalls-chrome-clean), " +
            "copyright (c) 2020 magnolia1234, MIT License. " +
            "Data only: no upstream code is executed."

    private val SHA_RE = Regex("[0-9a-f]{64}")

    /** Browser-only fields: recognized, recorded as limitations, never applied. */
    private val BROWSER_ONLY_FIELDS = setOf(
        "allow_cookies",
        "block_regex",
        "block_js_inline",
        "cs_dompurify",
        "cs_all_frames",
        "cs_clear_lclstrg",
        "remove_cookies",
        "remove_cookies_select_drop",
        "remove_cookies_select_hold",
        "cs_param",
        "add_ext_link",
        "add_ext_link_type",
        "amp_unhide",
    )

    @Throws(SiteCatalogException::class)
    fun importZip(zipBytes: ByteArray, sourceSha256: String, sourceUrl: String): SiteCatalog {
        try {
            val archive = UpdateArchive.readArchive(zipBytes, LABEL)
            val version = UpdateArchive.parseManifestVersion(archive.manifestJson, LABEL)
            return importParsed(archive.sitesJs, version, archive.license, sourceSha256, sourceUrl)
        } catch (e: JsDataUnsupportedException) {
            throw SiteCatalogUnsupportedException(e.message ?: "$LABEL is not supported", e)
        } catch (e: JsDataException) {
            throw SiteCatalogException(e.message ?: "$LABEL failed", e)
        }
    }

    /**
     * Imports already-decoded texts (unit-test fixtures use this; production
     * uses [importZip]). [license] is the upstream LICENSE text or null.
     */
    @Throws(SiteCatalogException::class)
    fun importText(
        sitesJs: String,
        manifestJson: String,
        license: String?,
        sourceSha256: String,
        sourceUrl: String,
    ): SiteCatalog {
        try {
            val version = UpdateArchive.parseManifestVersion(manifestJson, LABEL)
            return importParsed(sitesJs, version, license, sourceSha256, sourceUrl)
        } catch (e: JsDataUnsupportedException) {
            throw SiteCatalogUnsupportedException(e.message ?: "$LABEL is not supported", e)
        } catch (e: JsDataException) {
            throw SiteCatalogException(e.message ?: "$LABEL failed", e)
        }
    }

    private fun importParsed(
        sitesJs: String,
        version: String,
        license: String?,
        sourceSha256: String,
        sourceUrl: String,
    ): SiteCatalog {
        if (sourceSha256.isNotEmpty() && !SHA_RE.matches(sourceSha256)) {
            throw SiteCatalogException("$LABEL has invalid sourceSha256: expected \"\" or 64 lowercase hex")
        }
        if (sourceUrl != SiteCatalog.OFFICIAL_UPDATE_URL) {
            throw SiteCatalogException("$LABEL has unexpected sourceUrl: expected the official update URL")
        }
        if (license == null) {
            throw SiteCatalogException(
                "$LABEL ZIP has no LICENSE next to \"sites.js\"; refusing a blank-license catalog",
            )
        }
        if (license.isBlank() || !license.startsWith("MIT License")) {
            throw SiteCatalogException("$LABEL LICENSE is not the upstream MIT text")
        }
        if (license.length > SiteCatalogLimits.MAX_LICENSE_LENGTH) {
            throw SiteCatalogException("$LABEL LICENSE is over ${SiteCatalogLimits.MAX_LICENSE_LENGTH} chars")
        }
        val roots = JsLiteral.findDefaultSitesRoots(sitesJs, LABEL)
        if (roots.isEmpty()) {
            throw SiteCatalogUnsupportedException("$LABEL \"sites.js\" has no \"defaultSites\" object")
        }
        if (roots.size > 1) {
            throw SiteCatalogUnsupportedException(
                "$LABEL \"sites.js\" has multiple \"defaultSites\" declarations: " +
                    "ambiguous, refusing to guess",
            )
        }
        val root = roots.single()
        val direct = JsLiteral.scanDirectEntries(sitesJs, root.open, root.close, LABEL)

        val seenKeys = HashSet<String>()
        val built = mutableListOf<BuiltRule>()
        val skipped = mutableListOf<SkippedSite>()
        val withheld = LinkedHashSet<String>()
        for (entry in direct) {
            if (entry.key.isBlank() || entry.key.length > SiteCatalogLimits.MAX_SITE_KEY_LENGTH) {
                skipped.add(SkippedSite(truncatedKey(entry.key), "site key is blank or overlong; skipped"))
                continue
            }
            if (entry.key.any { it == '\n' || it == '\r' }) {
                skipped.add(SkippedSite(truncatedKey(entry.key), "site key contains a newline; skipped"))
                continue
            }
            if (!seenKeys.add(entry.key)) {
                skipped.add(SkippedSite(entry.key, "duplicate site entry; first wins"))
                continue
            }
            try {
                buildEntry(sitesJs, entry, built, skipped, withheld)
            } catch (e: SiteCatalogOverflowException) {
                // Catalog-wide safety bound: never truncate restriction
                // authority into a per-site skip with half-applied parents.
                throw e
            } catch (e: JsDataException) {
                // Delimited but unreadable/unsupported site: skip it, keep the rest.
                skipped.add(SkippedSite(entry.key, shortReason(e.message ?: "unreadable entry")))
            } catch (e: Exception) {
                // One site must never kill the catalog; the reason keeps it explicit.
                skipped.add(
                    SkippedSite(entry.key, shortReason("importer failed: ${e.javaClass.name}: ${e.message}")),
                )
            }
        }
        resolveConflicts(built, skipped, withheld)
        return assemble(version, sourceSha256, sourceUrl, license, built, skipped, withheld)
    }

    /** Single-line rendering: decoded `\n` escapes must never reach the codec. */
    private fun sanitizeLine(text: String): String {
        if (!text.any { it == '\n' || it == '\r' }) return text
        return text.replace('\r', ' ').replace('\n', ' ')
    }

    private fun truncatedKey(key: String): String =
        if (key.isBlank()) "(blank site key)"
        else sanitizeLine(key.take(SiteCatalogLimits.MAX_SITE_KEY_LENGTH))

    private fun shortReason(message: String): String {
        val stripped = message.removePrefix("$LABEL \"sites.js\" ").removePrefix("$LABEL ")
        val single = sanitizeLine(stripped)
        return if (single.length > SiteCatalogLimits.MAX_NOTE_LENGTH) {
            single.take(SiteCatalogLimits.MAX_NOTE_LENGTH - 1) + "…"
        } else {
            single
        }
    }

    // ------------------------------------------------------------------
    // Per-entry build
    // ------------------------------------------------------------------

    private class BuiltRule(
        val siteKey: String,
        val matchDomains: LinkedHashSet<String>,
        val excludedDomains: Set<String>,
        val scope: HostScope,
        var request: SiteRequestPolicy,
        var html: SiteHtmlDirectives,
        val notes: NoteSink,
        var lostToConflict: Boolean = false,
        var mergedInto: String? = null,
        /** Parent domains taken over by replacement exceptions (not lost). */
        var strippedByExceptions: Int = 0,
    )

    /** What one rule-object build produced: the rule (already stored) plus the domains it claims. */
    private class RuleResult(val rule: BuiltRule?, val claimed: Set<String>)

    private class NoteSink {
        val notes = mutableListOf<String>()
        var dropped = 0

        fun add(text: String) {
            val single = sanitizeLine(text)
            val t = if (single.length > SiteCatalogLimits.MAX_NOTE_LENGTH) {
                single.take(SiteCatalogLimits.MAX_NOTE_LENGTH - 1) + "…"
            } else {
                single
            }
            if (notes.size < SiteCatalogLimits.MAX_NOTES_PER_RULE) notes.add(t) else dropped++
        }

        fun finish(): List<String> {
            if (dropped == 0) return notes.toList()
            return notes.dropLast(1) + "(and $dropped more notes dropped)"
        }
    }

    private fun buildEntry(
        sitesJs: String,
        entry: JsLiteral.DirectEntry,
        built: MutableList<BuiltRule>,
        skipped: MutableList<SkippedSite>,
        withheld: MutableSet<String>,
    ) {
        val (value, end) = JsLiteral.readLiteral(sitesJs, entry.valuePos, LABEL, "\"${entry.key}\"")
        if (value !is JsValue.Obj) {
            skipped.add(SkippedSite(entry.key, "entry is not a static object; skipped"))
            return
        }
        // The entry value must end at its closing brace: a chained suffix
        // (call, property access, operator) is not static data.
        val j = JsLiteral.skipWsAndComments(sitesJs, end, LABEL)
        if (j >= sitesJs.length) {
            skipped.add(SkippedSite(entry.key, "entry is unterminated; skipped"))
            return
        }
        val next = sitesJs[j]
        if (next != ',' && next != '}') {
            skipped.add(
                SkippedSite(entry.key, "entry has unsupported suffix \"$next\"; skipped"),
            )
            return
        }
        buildRuleObject(entry.key, value.entries, parentRule = null, built, skipped, withheld)
    }

    /**
     * Builds one rule object: a top-level entry ([parentRule] null) or a
     * replacement exception. Exceptions never inherit parent fields; on
     * return the caller strips [RuleResult.claimed] from the parent's match
     * set and records a withheld barrier, so the replacement (or a
     * withhold, when the exception itself is unusable) wins over the parent
     * for those domains, including narrower subtrees the exact strip misses.
     * When an exception's domains cannot be identified at all, the parent's
     * own directives are withheld instead of pretending no exception exists.
     */
    private fun buildRuleObject(
        siteKey: String,
        fields: Map<String, JsValue>,
        parentRule: BuiltRule?,
        built: MutableList<BuiltRule>,
        skipped: MutableList<SkippedSite>,
        withheld: MutableSet<String>,
    ): RuleResult {
        val isException = parentRule != null
        val notes = NoteSink()

        if ("block_regex_general" in fields) {
            if (isException) {
                val claimed = exceptionClaimedDomains(fields, parentRule!!.notes)
                if (claimed.isEmpty()) {
                    withholdParentForUnknownException(parentRule, "infrastructure block_regex_general")
                } else {
                    parentRule.notes.add("exception uses infrastructure block_regex_general; withheld")
                }
                return RuleResult(null, claimed)
            }
            skipped.add(
                SkippedSite(siteKey, "infrastructure rule (block_regex_general), not a publisher rule"),
            )
            return RuleResult(null, emptySet())
        }

        val domainRaw = fields["domain"]
        if (domainRaw == null) {
            if (isException) {
                withholdParentForUnknownException(parentRule!!, "no domain")
                return RuleResult(null, emptySet())
            }
            skipped.add(SkippedSite(siteKey, "entry is missing required field \"domain\"; skipped"))
            return RuleResult(null, emptySet())
        }
        val domainNames = readDomainList(domainRaw, isException, notes)
        if (domainNames == null) {
            if (isException) {
                withholdParentForUnknownException(parentRule!!, "unsupported \"domain\" shape")
                return RuleResult(null, emptySet())
            }
            skipped.add(SkippedSite(siteKey, "field \"domain\" has unsupported shape; skipped"))
            return RuleResult(null, emptySet())
        }

        // Match domains: a `group` replaces the domain list exactly like the
        // extension (even when empty); otherwise the domain field scopes.
        val matchDomains = LinkedHashSet<String>()
        if (!isException && "group" in fields) {
            val groupRaw = fields["group"]!!
            val members = readGroupMembers(groupRaw, notes)
            if (members == null) {
                skipped.add(SkippedSite(siteKey, "field \"group\" has unsupported shape; skipped"))
                return RuleResult(null, emptySet())
            }
            var invalid = 0
            for (member in members) {
                val normalized = SiteDomains.normalizeRuleDomain(member)
                if (normalized == null) {
                    invalid++
                    if (invalid <= 3) {
                        notes.add("group member \"$member\" skipped: ${SiteDomains.invalidReason(member)}")
                    }
                } else {
                    matchDomains.add(normalized)
                }
            }
            if (invalid > 3) notes.add("and ${invalid - 3} more invalid group members skipped")
            val pseudo = domainNames.firstOrNull()?.let { isPseudoDomain(it) } ?: true
            if (!pseudo && domainNames.isNotEmpty()) {
                notes.add("publisher domain \"${domainNames.first()}\" is shadowed by \"group\"")
            }
            if (matchDomains.isEmpty()) {
                if (members.isEmpty()) {
                    skipped.add(SkippedSite(siteKey, "empty opt-in group contains no domains"))
                } else {
                    skipped.add(SkippedSite(siteKey, "group has no valid match domains; skipped"))
                }
                return RuleResult(null, emptySet())
            }
        } else {
            if (domainNames.isEmpty()) {
                withholdParentForUnknownException(parentRule!!, "no usable domain entries")
                return RuleResult(null, emptySet())
            }
            if (!isException && isPseudoDomain(domainNames.single())) {
                skipped.add(SkippedSite(siteKey, "settings marker \"${domainNames.single()}\", not a host rule"))
                return RuleResult(null, emptySet())
            }
            for (name in domainNames) {
                val normalized = SiteDomains.normalizeRuleDomain(name)
                if (normalized == null) {
                    if (isException) {
                        notes.add("exception domain \"$name\" skipped: ${SiteDomains.invalidReason(name)}")
                    } else {
                        skipped.add(
                            SkippedSite(siteKey, "field \"domain\" $name: ${SiteDomains.invalidReason(name)}; skipped"),
                        )
                        return RuleResult(null, emptySet())
                    }
                } else {
                    matchDomains.add(normalized)
                }
            }
            if (matchDomains.isEmpty()) {
                parentRule!!.notes.add("exception has no valid domain; ignored")
                return RuleResult(null, emptySet())
            }
            if (isException) {
                val outside = matchDomains.filter { it !in parentRule!!.matchDomains }
                if (outside.isNotEmpty()) {
                    notes.add(
                        "exception domains are not parent group members, applied independently: " +
                            outside.sorted().take(4).joinToString(", "),
                    )
                }
            }
        }

        if (isException && ("group" in fields || "exception" in fields)) {
            // Nested structure inside an exception: withhold its domains so
            // neither the exception nor the parent covers them.
            parentRule!!.notes.add("exception has unsupported nested group/exception; its domains are withheld")
            return RuleResult(null, matchDomains.toSet())
        }

        if (matchDomains.size > SiteCatalogLimits.MAX_MATCH_DOMAINS_PER_RULE) {
            val drop = matchDomains.size - SiteCatalogLimits.MAX_MATCH_DOMAINS_PER_RULE
            val kept = matchDomains.take(SiteCatalogLimits.MAX_MATCH_DOMAINS_PER_RULE).toSet()
            val dropped = matchDomains - kept
            matchDomains.retainAll(kept)
            // Dropped claims are barred, not silently released: without the
            // barrier a broader rule would reapply where this rule's exact
            // directives were lost. Overflow rejects the catalog.
            addWithheld(withheld, dropped)
            notes.add("$drop match domains dropped (per-rule bound); dropped domains barred")
        }

        val excludedRaw = fields["excluded_domains"]
        val excluded = readExcluded(excludedRaw, notes)
        if (excluded == null) {
            // Null means an unsupported shape or an over-bound list; either
            // way the restriction cannot be honored, so the affected rule is
            // withheld rather than broadened by trimming.
            val overBound = excludedRaw is JsValue.Arr
            if (isException) {
                val why = if (overBound) "lists too many domains" else "has unsupported shape"
                parentRule!!.notes.add("exception \"excluded_domains\" $why; its domains are withheld")
                return RuleResult(null, matchDomains.toSet())
            }
            // The rule's match domains are barred (not merely dropped),
            // so a broader rule cannot reapply its directives where this
            // rule's restrictions were lost. Overflow rejects the catalog.
            addWithheld(withheld, matchDomains.toSet())
            val reason = if (overBound) {
                "field \"excluded_domains\" lists more than " +
                    "${SiteCatalogLimits.MAX_EXCLUDED_PER_RULE} domains; " +
                    "rule withheld and its match domains barred"
            } else {
                "field \"excluded_domains\" has unsupported shape; " +
                    "rule withheld and its match domains barred"
            }
            skipped.add(SkippedSite(siteKey, reason))
            return RuleResult(null, emptySet())
        }

        val directives = buildDirectives(fields, notes)
        val scope = SiteCompatPolicy.scopeFor(matchDomains)
        val rule = BuiltRule(siteKey, matchDomains, excluded, scope, directives.request, directives.html, notes)
        built.add(rule)

        if (!isException && "exception" in fields) {
            val raw = fields["exception"]!!
            if (raw !is JsValue.Arr) {
                withholdParentForUnknownException(rule, "\"exception\" is not an array")
            } else {
                raw.items.forEachIndexed { index, item ->
                    val excKey = "$siteKey › exception ${index + 1}"
                    if (excKey.length > SiteCatalogLimits.MAX_SITE_KEY_LENGTH) {
                        withholdParentForUnknownException(rule, "exception ${index + 1} key is too long")
                        return@forEachIndexed
                    }
                    if (item !is JsValue.Obj) {
                        withholdParentForUnknownException(rule, "exception ${index + 1} is not an object")
                        return@forEachIndexed
                    }
                    val result = buildRuleObject(excKey, item.entries, rule, built, skipped, withheld)
                    if (result.claimed.isNotEmpty()) {
                        val before = rule.matchDomains.size
                        rule.matchDomains.removeAll(result.claimed)
                        rule.strippedByExceptions += before - rule.matchDomains.size
                        if (result.rule == null) {
                            // Unusable exception: exact stripping misses
                            // narrower subtrees, so bar the claimed domains
                            // against any less-specific fallback.
                            addWithheld(withheld, result.claimed)
                        }
                    }
                }
            }
        }
        return RuleResult(rule, matchDomains.toSet())
    }

    /** Valid exception domains claimed for withhold when the exception body itself is unusable. */
    private fun exceptionClaimedDomains(fields: Map<String, JsValue>, notes: NoteSink): Set<String> {
        val raw = fields["domain"] ?: return emptySet()
        val names = readDomainList(raw, true, notes) ?: return emptySet()
        return names.mapNotNull { SiteDomains.normalizeRuleDomain(it) }.toSet()
    }

    /**
     * Withholds a parent rule's own directives when an exception exists but
     * its domains cannot be identified: clearing the headers and cleanup the
     * exception would have replaced is the only safe replacement for an
     * unknown scope. Neighboring rules are untouched.
     */
    private fun withholdParentForUnknownException(rule: BuiltRule, why: String) {
        rule.request = SiteRequestPolicy(null, null, null, null, emptyMap())
        rule.html = SiteHtmlDirectives(emptyList(), null, null)
        rule.notes.add("exception with $why; parent directives withheld")
    }

    /**
     * Records barrier domains. The bound is fail-closed: exceeding it
     * rejects the whole candidate catalog ([SiteCatalogOverflowException],
     * which escapes per-site isolation) instead of truncating restriction
     * authority and leaking parent directives onto the dropped domains.
     */
    private fun addWithheld(withheld: MutableSet<String>, domains: Set<String>) {
        for (domain in domains.sorted()) {
            if (domain in withheld) continue
            if (withheld.size >= SiteCatalogLimits.MAX_WITHHELD_DOMAINS) {
                throw SiteCatalogOverflowException(
                    "$LABEL needs more than ${SiteCatalogLimits.MAX_WITHHELD_DOMAINS} withheld domains: " +
                        "refusing a truncated catalog that would leak parent directives",
                )
            }
            withheld.add(domain)
        }
    }

    private fun isPseudoDomain(name: String): Boolean = name.startsWith("#")

    /**
     * Reads a `domain` field: a string at top level, a string or string
     * array inside exceptions. Null when the shape is unsupported.
     */
    private fun readDomainList(raw: JsValue, isException: Boolean, notes: NoteSink): List<String>? {
        return when (raw) {
            is JsValue.Str -> listOf(raw.value)
            is JsValue.Arr -> {
                if (!isException) return null
                val out = mutableListOf<String>()
                for (item in raw.items) {
                    if (item !is JsValue.Str) {
                        notes.add("exception domain entry is not a string; skipped")
                        continue
                    }
                    out.add(item.value)
                }
                out
            }
            else -> null
        }
    }

    /**
     * Reads a `group` field: an array of strings, or (as the extension
     * also accepts) one comma-separated string. Null when unsupported.
     */
    private fun readGroupMembers(raw: JsValue, notes: NoteSink): List<String>? {
        return when (raw) {
            is JsValue.Arr -> {
                val out = mutableListOf<String>()
                for (item in raw.items) {
                    if (item !is JsValue.Str) {
                        notes.add("group member is not a string; skipped")
                        continue
                    }
                    out.add(item.value)
                }
                out
            }
            is JsValue.Str -> raw.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            else -> null
        }
    }

    /**
     * Reads `excluded_domains`. Null withholds the affected rule: an
     * unsupported shape or an over-bound list cannot be honored, and
     * trimming the restriction would broaden the rule. Single invalid
     * entries are skipped with a note; they could never match anyway.
     */
    private fun readExcluded(raw: JsValue?, notes: NoteSink): LinkedHashSet<String>? {
        if (raw == null) return LinkedHashSet()
        if (raw !is JsValue.Arr) return null
        val out = LinkedHashSet<String>()
        for (item in raw.items) {
            if (item !is JsValue.Str) {
                notes.add("excluded domain is not a string; skipped")
                continue
            }
            val normalized = SiteDomains.normalizeRuleDomain(item.value)
            if (normalized == null) {
                notes.add("excluded domain \"${item.value}\" skipped: ${SiteDomains.invalidReason(item.value)}")
                continue
            }
            out.add(normalized)
            if (out.size > SiteCatalogLimits.MAX_EXCLUDED_PER_RULE) {
                notes.add(
                    "excluded domains list more than ${SiteCatalogLimits.MAX_EXCLUDED_PER_RULE}; " +
                        "rule withheld",
                )
                return null
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Directives
    // ------------------------------------------------------------------

    private class Directives(val request: SiteRequestPolicy, val html: SiteHtmlDirectives)

    private fun buildDirectives(fields: Map<String, JsValue>, notes: NoteSink): Directives {
        val request = buildRequest(fields, notes)
        val html = buildHtml(fields, notes)
        for (field in fields.keys) {
            if (field in BROWSER_ONLY_FIELDS) {
                notes.add("uses $field: browser-only, not applied natively")
            } else if (
                field !in NATIVE_FIELDS && field != "domain" && field != "group" &&
                field != "exception" && field != "excluded_domains"
            ) {
                notes.add("unknown field \"$field\" ignored; the site may rely on it")
            }
        }
        return Directives(request, html)
    }

    private val NATIVE_FIELDS = setOf(
        "useragent", "useragent_custom", "referer", "referer_custom", "headers_custom",
        "headers_filter", "random_ip", "cs_code", "ld_json", "ld_json_url", "amp_redirect",
    )

    private fun buildRequest(fields: Map<String, JsValue>, notes: NoteSink): SiteRequestPolicy {
        if ("headers_filter" in fields) {
            notes.add(
                "request headers withheld: headers_filter scopes the extension's header rule " +
                    "to matching URLs; native applies no path-scoped headers",
            )
            return SiteRequestPolicy(null, null, null, null, emptyMap())
        }
        if ("random_ip" in fields) {
            notes.add(
                "request headers withheld: random_ip sends a per-request spoofed " +
                    "X-Forwarded-For the native engine does not reproduce",
            )
            return SiteRequestPolicy(null, null, null, null, emptyMap())
        }

        var userAgent: String? = null
        var userAgentSource: String? = null
        val uaRaw = fields["useragent"]
        var knownUa: KnownBot? = null
        if (uaRaw != null) {
            if (uaRaw is JsValue.Str) {
                knownUa = KnownBot.byId(uaRaw.value)
                if (knownUa == null) {
                    notes.add("unknown useragent \"${uaRaw.value}\"; no native User-Agent applied")
                }
            } else {
                notes.add("field \"useragent\" is not a string; ignored")
            }
        }
        val customRaw = fields["useragent_custom"]
        var customUa: String? = null
        if (customRaw != null) {
            if (customRaw is JsValue.Str) {
                val reason = invalidUaReason(customRaw.value)
                if (reason == null) {
                    customUa = customRaw.value
                } else {
                    notes.add("useragent_custom invalid ($reason); User-Agent withheld")
                }
            } else {
                notes.add("field \"useragent_custom\" is not a string; ignored")
            }
        }
        if (knownUa != null) {
            userAgent = knownUa.userAgent
            userAgentSource = knownUa.id
            if (customUa != null) notes.add("useragent_custom is shadowed by useragent \"${knownUa.id}\"")
            if (knownUa == KnownBot.GOOGLEBOT) {
                notes.add("upstream googlebot also sends X-Forwarded-For 66.249.66.1; native omits it")
            }
        } else if (customUa != null) {
            userAgent = customUa
            userAgentSource = "custom"
        }

        var referer: String? = null
        var refererSource: String? = null
        val hasUaFields = "useragent" in fields || "useragent_custom" in fields || "headers_custom" in fields
        val hasRefererFields = "referer" in fields || "referer_custom" in fields
        if (hasUaFields && hasRefererFields) {
            // The extension's referer branch is `else if` after the
            // useragent branch: referer fields never apply alongside them.
            notes.add("referer fields are shadowed by useragent fields; ignored")
        } else if (!hasUaFields) {
            val refRaw = fields["referer"]
            var preset: RefererPreset? = null
            if (refRaw != null) {
                if (refRaw is JsValue.Str) {
                    preset = RefererPreset.byId(refRaw.value)
                    if (preset == null) notes.add("unknown referer \"${refRaw.value}\"; no native Referer applied")
                } else {
                    notes.add("field \"referer\" is not a string; ignored")
                }
            }
            val customRefRaw = fields["referer_custom"]
            var customRef: String? = null
            if (customRefRaw != null) {
                if (customRefRaw is JsValue.Str) {
                    val reason = SiteRequests.invalidRefererReason(customRefRaw.value, SiteHeaders.MAX_VALUE_LENGTH)
                    if (reason == null) {
                        customRef = customRefRaw.value
                    } else {
                        notes.add("referer_custom invalid ($reason); Referer withheld")
                    }
                } else {
                    notes.add("field \"referer_custom\" is not a string; ignored")
                }
            }
            if (customRef != null) {
                referer = customRef
                refererSource = "custom"
                if (preset != null) notes.add("referer \"${preset.id}\" is shadowed by referer_custom")
            } else if (preset != null) {
                referer = preset.url
                refererSource = preset.id
            }
        }
        if (referer == null && knownUa == KnownBot.GOOGLEBOT) {
            referer = RefererPreset.GOOGLE.url
            refererSource = "googlebot"
        }

        val extras = LinkedHashMap<String, String>()
        val extrasRaw = fields["headers_custom"]
        if (extrasRaw != null) {
            if (extrasRaw is JsValue.Obj) {
                if (extrasRaw.entries.size > SiteCatalogLimits.MAX_EXTRA_HEADERS) {
                    notes.add("headers_custom beyond ${SiteCatalogLimits.MAX_EXTRA_HEADERS} entries dropped")
                }
                val seenLower = HashSet<String>()
                var collision = false
                for ((name, value) in extrasRaw.entries.entries.take(SiteCatalogLimits.MAX_EXTRA_HEADERS)) {
                    if (!SiteHeaders.isValidName(name)) {
                        notes.add("headers_custom has invalid name \"$name\"; entry skipped")
                        continue
                    }
                    if (name.lowercase() in SiteHeaders.FORBIDDEN_REQUEST_NAMES) {
                        notes.add("headers_custom \"$name\" is never set from rule data; entry skipped")
                        continue
                    }
                    if (!seenLower.add(name.lowercase())) {
                        collision = true
                        continue
                    }
                    if (value !is JsValue.Str) {
                        notes.add("headers_custom \"$name\" is not a string; entry skipped")
                        continue
                    }
                    val reason = invalidExtraValueReason(value.value)
                    if (reason != null) {
                        notes.add("headers_custom \"$name\" invalid ($reason); entry skipped")
                        continue
                    }
                    extras[name] = value.value
                }
                if (collision) {
                    notes.add("headers_custom has case-colliding names; all custom headers withheld")
                    extras.clear()
                }
            } else {
                notes.add("field \"headers_custom\" is not an object; ignored")
            }
        }

        return SiteRequestPolicy(userAgent, userAgentSource, referer, refererSource, extras)
    }

    private fun invalidUaReason(value: String): String? {
        if (value.isBlank()) return "blank"
        if (value.any { it == '\n' || it == '\r' }) return "must be a single line"
        val bad = SiteHeaders.findInvalidChar(value)
        if (bad != null) return "invalid header character ${SiteHeaders.describeChar(bad)}"
        if (value.length > SiteHeaders.MAX_VALUE_LENGTH) return "over ${SiteHeaders.MAX_VALUE_LENGTH} chars"
        return null
    }

    private fun invalidExtraValueReason(value: String): String? {
        if (value.isEmpty()) return "empty"
        if (value.first().isWhitespace() || value.last().isWhitespace()) {
            return "leading or trailing whitespace"
        }
        if (value.isBlank()) return "blank"
        if (value.any { it == '\n' || it == '\r' }) return "must be a single line"
        val bad = SiteHeaders.findInvalidChar(value)
        if (bad != null) return "invalid header character ${SiteHeaders.describeChar(bad)}"
        if (value.length > SiteHeaders.MAX_VALUE_LENGTH) return "over ${SiteHeaders.MAX_VALUE_LENGTH} chars"
        return null
    }

    private fun buildHtml(fields: Map<String, JsValue>, notes: NoteSink): SiteHtmlDirectives {
        val cleanup = mutableListOf<CleanupOp>()
        val opCount = intArrayOf(0)
        // Next cleanup group id, unique within this rule (nested `elems`
        // share the counter through the recursion below).
        val groups = intArrayOf(0)
        val csRaw = fields["cs_code"]
        if (csRaw != null) {
            parseCsCode(csRaw, cleanup, opCount, groups, notes)
        }
        var ldJson: LdJsonRule? = null
        if ("ld_json" in fields && "ld_json_url" in fields) {
            notes.add("both ld_json and ld_json_url are present; ld_json wins")
        }
        val ldRaw = fields["ld_json"] ?: fields["ld_json_url"]
        val ldKind = if ("ld_json" in fields) "ld_json" else "ld_json_url"
        if (ldRaw != null) {
            ldJson = parseLdJson(ldKind, ldRaw, notes)
            if (ldJson != null) notes.add("$ldKind is recorded for future use; not applied")
        }
        var ampRedirect: AmpRedirectRule? = null
        val ampRaw = fields["amp_redirect"]
        if (ampRaw != null) {
            ampRedirect = parseAmpRedirect(ampRaw, notes)
            if (ampRedirect != null) notes.add("amp_redirect is recorded for future use; not applied")
        }
        return SiteHtmlDirectives(cleanup, ldJson, ampRedirect)
    }

    // ------------------------------------------------------------------
    // cs_code cleanup primitives
    // ------------------------------------------------------------------

    private fun parseCsCode(
        raw: JsValue,
        out: MutableList<CleanupOp>,
        opCount: IntArray,
        groups: IntArray,
        notes: NoteSink,
    ) {
        val items: List<JsValue> = when (raw) {
            is JsValue.Arr -> raw.items
            is JsValue.Obj -> listOf(raw)
            is JsValue.Str -> {
                // The extension runs JSON.parse then iterates an array, so a
                // JSON string may hold one object or an array of them. Decoded
                // as literal JSON data only; trailing tokens reject the whole
                // string rather than silently truncating it.
                val decoded = try {
                    val tokener = JSONTokener(raw.value)
                    val value = tokener.nextValue()
                    while (tokener.more()) {
                        if (!tokener.next().isWhitespace()) {
                            throw JSONException("trailing characters after JSON value")
                        }
                    }
                    jsonToJs(value, 0)
                } catch (e: JSONException) {
                    notes.add("cs_code JSON string does not decode; cleanup skipped")
                    return
                } catch (e: JsDataException) {
                    notes.add("cs_code JSON string ${shortReason(e.message ?: "is unsupported")}; cleanup skipped")
                    return
                }
                when (decoded) {
                    is JsValue.Obj -> listOf(decoded)
                    is JsValue.Arr -> decoded.items
                    else -> {
                        notes.add("cs_code JSON string is not an object or array; cleanup skipped")
                        return
                    }
                }
            }
            else -> {
                notes.add("cs_code has unsupported shape; cleanup skipped")
                return
            }
        }
        items.forEachIndexed { index, item ->
            if (item !is JsValue.Obj) {
                notes.add("cs_code item ${index + 1} is not an object; skipped")
                return@forEachIndexed
            }
            out.addAll(parseCleanupOp(item.entries, 0, opCount, groups, notes))
        }
    }

    private fun jsonToJs(value: Any?, depth: Int): JsValue {
        if (depth > JsLiteral.MAX_LITERAL_DEPTH) {
            throw JsDataUnsupportedException("nests deeper than ${JsLiteral.MAX_LITERAL_DEPTH} levels")
        }
        return when (value) {
            null, JSONObject.NULL -> JsValue.Null
            is Boolean -> JsValue.Bool(value)
            is Number -> JsValue.Num(value.toString())
            is String -> JsValue.Str(value)
            is JSONObject -> {
                if (value.length() > JsLiteral.MAX_LITERAL_KEYS) {
                    throw JsDataUnsupportedException("has more than ${JsLiteral.MAX_LITERAL_KEYS} fields")
                }
                val entries = LinkedHashMap<String, JsValue>()
                for (key in value.keys()) {
                    entries[key] = jsonToJs(value.opt(key), depth + 1)
                }
                JsValue.Obj(entries)
            }
            is JSONArray -> {
                if (value.length() > JsLiteral.MAX_LITERAL_ITEMS) {
                    throw JsDataUnsupportedException("has more than ${JsLiteral.MAX_LITERAL_ITEMS} items")
                }
                JsValue.Arr((0 until value.length()).map { jsonToJs(value.opt(it), depth + 1) })
            }
            else -> throw JsDataUnsupportedException("has unsupported JSON value")
        }
    }

    /**
     * Parses one `cs_code` object into zero or more cleanup ops. Top-level
     * priority (`add_style`, `hide_elem`, `rm_elem_wait`, `cond`) is
     * else-if like the extension, but inside one `cond` every supported
     * action (`rm_elem`, `rm_class`, `rm_attrib`) is an independent `if` and
     * all are preserved as sibling ops in extension order. Nothing supported
     * is lost through an early return; every unsupported action is noted.
     */
    private fun parseCleanupOp(
        fields: Map<String, JsValue>,
        depth: Int,
        opCount: IntArray,
        groups: IntArray,
        notes: NoteSink,
    ): List<CleanupOp> {
        for (field in fields.keys) {
            if (field !in CS_CODE_KEYS) notes.add("cs_code op has unknown key \"$field\"")
        }
        val hasStyle = "add_style" in fields
        val hasHide = "hide_elem" in fields
        val hasWait = "rm_elem_wait" in fields
        val hasCond = "cond" in fields
        val actions = listOf(hasStyle, hasHide, hasWait, hasCond).count { it }
        if (actions > 1) notes.add("cs_code op has multiple actions; first wins (extension order)")
        // Extension priority: add_style, hide_elem, rm_elem_wait, then cond.
        if (hasStyle) {
            notes.add("cs_code add_style is browser-only; skipped")
            return emptyList()
        }
        if (hasHide) {
            val raw = fields["hide_elem"]!!
            if (raw !is JsValue.Str) {
                notes.add("cs_code hide_elem is not a string; skipped")
                return emptyList()
            }
            if ("elems" in fields) notes.add("cs_code elems without cond; skipped")
            val group = groups[0]++
            return listOfNotNull(newOp(CleanupKind.HIDE, raw.value, emptyList(), group, opCount, notes))
        }
        if (hasWait) {
            notes.add("cs_code rm_elem_wait is browser-only; skipped")
            return emptyList()
        }
        if (!hasCond) {
            notes.add("cs_code op has no supported action; skipped")
            return emptyList()
        }
        val condRaw = fields["cond"]!!
        if (condRaw !is JsValue.Str) {
            notes.add("cs_code cond is not a string; skipped")
            if ("set_attrib" in fields) notes.add("cs_code set_attrib is not supported; skipped")
            return emptyList()
        }
        val cond = condRaw.value
        val selectorReason =
            SiteSelectors.invalidSelectorReason(cond, SiteCatalogLimits.MAX_SELECTOR_LENGTH)
        if (selectorReason != null) {
            notes.add("cs_code selector $selectorReason; skipped")
            if ("set_attrib" in fields) notes.add("cs_code set_attrib is not supported; skipped")
            return emptyList()
        }
        // One group for every action of this cond: the executor snapshots
        // the matched nodes once per group and applies all siblings to that
        // original set (re-selecting after a mutation would miss nodes).
        val group = groups[0]++
        val ops = mutableListOf<CleanupOp>()
        if ("rm_elem" in fields) {
            val truthy = when (val v = fields["rm_elem"]!!) {
                is JsValue.Bool -> v.value
                is JsValue.Num -> v.raw.toDoubleOrNull()?.let { it != 0.0 }
                is JsValue.Str -> v.value.isNotEmpty()
                is JsValue.Null -> false
                else -> null
            }
            if (truthy == null) {
                notes.add("cs_code rm_elem has unsupported value; skipped")
            } else if (truthy) {
                newOp(CleanupKind.REMOVE, cond, emptyList(), group, opCount, notes)?.let { ops.add(it) }
            }
        }
        if ("rm_class" in fields) {
            val raw = fields["rm_class"]!!
            if (raw !is JsValue.Str) {
                notes.add("cs_code rm_class is not a string; skipped")
            } else {
                val names = raw.value.split(',', '|').map { it.trim() }
                val valid = mutableListOf<String>()
                for (name in names) {
                    if (SiteSelectors.isValidClassName(name)) {
                        valid.add(name)
                    } else {
                        notes.add("cs_code rm_class \"$name\" is invalid; entry skipped")
                    }
                }
                if (valid.isNotEmpty()) {
                    newOp(CleanupKind.REMOVE_CLASSES, cond, valid, group, opCount, notes)?.let { ops.add(it) }
                } else {
                    notes.add("cs_code rm_class has no valid names; skipped")
                }
            }
        }
        if ("rm_attrib" in fields) {
            val raw = fields["rm_attrib"]!!
            if (raw !is JsValue.Str) {
                notes.add("cs_code rm_attrib is not a string; skipped")
            } else {
                val names = raw.value.split('|').map { it.trim() }
                val valid = mutableListOf<String>()
                for (name in names) {
                    if (SiteSelectors.isValidAttrName(name)) {
                        valid.add(name)
                    } else {
                        notes.add("cs_code rm_attrib \"$name\" is invalid; entry skipped")
                    }
                }
                if (valid.isNotEmpty()) {
                    newOp(CleanupKind.REMOVE_ATTRS, cond, valid, group, opCount, notes)?.let { ops.add(it) }
                } else {
                    notes.add("cs_code rm_attrib has no valid names; skipped")
                }
            }
        }
        if ("set_attrib" in fields) notes.add("cs_code set_attrib is not supported; skipped")
        if ("elems" in fields) {
            if (ops.isEmpty()) {
                notes.add("cs_code elems without a supported action; skipped")
            } else {
                val children = parseElemsChildren(fields["elems"], depth, opCount, groups, notes)
                if (children.isNotEmpty()) {
                    // One shared nested group: the extension runs nested
                    // selectors globally once when the outer cond matches, so
                    // the children ride on the first sibling only.
                    ops[0] = ops[0].copy(children = children)
                }
            }
        }
        if (ops.isEmpty() && "rm_elem" !in fields && "rm_attrib" !in fields && "rm_class" !in fields) {
            notes.add("cs_code cond has no supported action; skipped")
        }
        return ops
    }

    private val CS_CODE_KEYS = setOf(
        "add_style", "hide_elem", "rm_elem_wait", "cond", "rm_elem",
        "rm_attrib", "rm_class", "set_attrib", "elems",
    )

    private fun newOp(
        kind: CleanupKind,
        selector: String,
        names: List<String>,
        group: Int,
        opCount: IntArray,
        notes: NoteSink,
    ): CleanupOp? {
        // Cond selectors are validated once by the caller; hide_elem arrives
        // here directly, so validate again (cheap, same message).
        val reason = SiteSelectors.invalidSelectorReason(selector, SiteCatalogLimits.MAX_SELECTOR_LENGTH)
        if (reason != null) {
            notes.add("cs_code selector $reason; skipped")
            return null
        }
        if (opCount[0] >= SiteCatalogLimits.MAX_CLEANUP_OPS) {
            notes.add("cleanup op bound reached; extras dropped")
            return null
        }
        opCount[0]++
        if (names.size > SiteCatalogLimits.MAX_CLEANUP_NAMES) {
            notes.add("cs_code names beyond ${SiteCatalogLimits.MAX_CLEANUP_NAMES} dropped")
            return CleanupOp(
                kind,
                selector,
                names.take(SiteCatalogLimits.MAX_CLEANUP_NAMES),
                group,
                emptyList(),
            )
        }
        return CleanupOp(kind, selector, names, group, emptyList())
    }

    /**
     * Parses one `elems` array into nested children. The extension runs these
     * globally (document scope) once when the outer `cond` matches at least
     * one node — never scoped to the outer's matched nodes — and the model
     * documents that; see [CleanupOp.children].
     */
    private fun parseElemsChildren(
        elemsRaw: JsValue?,
        depth: Int,
        opCount: IntArray,
        groups: IntArray,
        notes: NoteSink,
    ): List<CleanupOp> {
        if (elemsRaw !is JsValue.Arr) {
            notes.add("cs_code elems is not an array; skipped")
            return emptyList()
        }
        if (depth + 1 > SiteCatalogLimits.MAX_CLEANUP_DEPTH) {
            notes.add("cs_code elems nests too deep; skipped")
            return emptyList()
        }
        val children = mutableListOf<CleanupOp>()
        elemsRaw.items.forEachIndexed { index, item ->
            if (item !is JsValue.Obj) {
                notes.add("cs_code elems item ${index + 1} is not an object; skipped")
                return@forEachIndexed
            }
            children.addAll(parseCleanupOp(item.entries, depth + 1, opCount, groups, notes))
        }
        return children
    }

    // ------------------------------------------------------------------
    // Future (typed, unapplied) selectors
    // ------------------------------------------------------------------

    private fun parseLdJson(kind: String, raw: JsValue, notes: NoteSink): LdJsonRule? {
        if (raw !is JsValue.Str) {
            notes.add("$kind is not a string; skipped")
            return null
        }
        val parts = raw.value.split('|')
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            notes.add("$kind needs paywall|article selectors; skipped")
            return null
        }
        val paywallReason =
            SiteSelectors.invalidSelectorReason(parts[0], SiteCatalogLimits.MAX_SELECTOR_LENGTH)
        if (paywallReason != null) {
            notes.add("$kind paywall selector $paywallReason; skipped")
            return null
        }
        val articleReason =
            SiteSelectors.invalidSelectorReason(parts[1], SiteCatalogLimits.MAX_SELECTOR_LENGTH)
        if (articleReason != null) {
            notes.add("$kind article selector $articleReason; skipped")
            return null
        }
        // Kept segments are always validated, even when the tail is
        // truncated: the cache codec rejects empty/oversized extras, so the
        // importer must never emit what the codec cannot read back.
        val extra = parts.drop(2)
        val kept = if (extra.size > SiteCatalogLimits.MAX_LD_JSON_EXTRA) {
            notes.add("$kind has extra segments; dropped")
            extra.take(SiteCatalogLimits.MAX_LD_JSON_EXTRA)
        } else {
            extra
        }
        for (segment in kept) {
            if (segment.isEmpty() ||
                segment.length > SiteCatalogLimits.MAX_LD_JSON_EXTRA_LENGTH ||
                segment.any { it == '\n' || it == '\r' }
            ) {
                notes.add("$kind has an invalid extra segment; skipped")
                return null
            }
        }
        return LdJsonRule(kind, parts[0], parts[1], kept)
    }

    private fun parseAmpRedirect(raw: JsValue, notes: NoteSink): AmpRedirectRule? {
        if (raw !is JsValue.Str) {
            notes.add("amp_redirect is not a string; skipped")
            return null
        }
        val parts = raw.value.split('|')
        if (parts[0].isBlank()) {
            notes.add("amp_redirect needs a paywall selector; skipped")
            return null
        }
        val reason = SiteSelectors.invalidSelectorReason(parts[0], SiteCatalogLimits.MAX_SELECTOR_LENGTH)
        if (reason != null) {
            notes.add("amp_redirect paywall selector $reason; skipped")
            return null
        }
        if (parts.size > 2) notes.add("amp_redirect has extra segments; dropped")
        val template = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        if (template != null) {
            if (template.length > SiteCatalogLimits.MAX_AMP_URL_LENGTH ||
                template.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }
            ) {
                notes.add("amp_redirect URL template is invalid; skipped")
                return null
            }
        }
        return AmpRedirectRule(parts[0], template)
    }

    // ------------------------------------------------------------------
    // Conflicts and assembly
    // ------------------------------------------------------------------

    private data class ConflictKey(
        val request: SiteRequestPolicy,
        val html: SiteHtmlDirectives,
        val excluded: Set<String>,
        val scope: HostScope,
    )

    /**
     * Resolves same-domain claims across rules: identical directives merge
     * into the first claimant, conflicting directives withhold the domain
     * from every claimant and bar it against broader fallback. Runs in
     * sorted domain order so notes are deterministic.
     */
    private fun resolveConflicts(
        built: MutableList<BuiltRule>,
        skipped: MutableList<SkippedSite>,
        withheld: MutableSet<String>,
    ) {
        val claims = LinkedHashMap<String, MutableList<Int>>()
        built.forEachIndexed { index, rule ->
            for (domain in rule.matchDomains.sorted()) {
                claims.getOrPut(domain) { mutableListOf() }.add(index)
            }
        }
        for (domain in claims.keys.sorted()) {
            val indices = claims[domain]!!
            if (indices.size < 2) continue
            val keys = indices.associateWith {
                val r = built[it]
                ConflictKey(r.request, r.html, r.excludedDomains, r.scope)
            }
            val first = indices.first()
            if (keys.values.all { it == keys[first] }) {
                for (loser in indices.drop(1)) {
                    built[loser].matchDomains.remove(domain)
                    if (built[loser].mergedInto == null) built[loser].mergedInto = built[first].siteKey
                }
                val from = indices.drop(1).map { built[it].siteKey }.distinct().take(3)
                built[first].notes.add(
                    "domain \"$domain\" also claimed by ${from.joinToString(", ") { "\"$it\"" }} " +
                        "with identical directives; merged",
                )
            } else {
                for (index in indices) {
                    built[index].matchDomains.remove(domain)
                    built[index].lostToConflict = true
                    val others = indices.filter { it != index }.map { built[it].siteKey }.distinct().take(3)
                    built[index].notes.add(
                        "match domain \"$domain\" withheld: conflicting directives with " +
                            others.joinToString(", ") { "\"$it\"" },
                    )
                }
                // Fail closed: a conflicting domain must be barred even
                // when the barrier set is full, so overflow rejects the
                // whole candidate catalog instead of leaking a fallback.
                addWithheld(withheld, setOf(domain))
            }
        }
        val empty = built.filter { it.matchDomains.isEmpty() }
        for (rule in empty) {
            if (rule.lostToConflict) {
                skipped.add(SkippedSite(rule.siteKey, "all match domains withheld (conflicting directives)"))
            } else if (rule.mergedInto != null) {
                skipped.add(SkippedSite(rule.siteKey, "duplicate directives merged into \"${rule.mergedInto}\""))
            } else if (rule.strippedByExceptions > 0) {
                val livesOn = built.any { it !== rule && it.siteKey.startsWith(rule.siteKey + " › exception") }
                if (!livesOn) {
                    skipped.add(
                        SkippedSite(rule.siteKey, "all match domains withheld (unusable exceptions)"),
                    )
                }
                // Else fully replaced by valid exceptions: the entry lives on
                // in the exception rules, so no skipped record is owed.
            } else {
                skipped.add(SkippedSite(rule.siteKey, "rule has no match domains; skipped"))
            }
        }
        built.removeAll { it.matchDomains.isEmpty() }
    }

    private fun assemble(
        version: String,
        sourceSha256: String,
        sourceUrl: String,
        license: String,
        built: List<BuiltRule>,
        skipped: MutableList<SkippedSite>,
        withheld: MutableSet<String>,
    ): SiteCatalog {
        val rules = mutableListOf<SiteRule>()
        var totalDomains = 0
        var droppedRules = 0
        for (candidate in built) {
            if (rules.size >= SiteCatalogLimits.MAX_RULES ||
                totalDomains + candidate.matchDomains.size > SiteCatalogLimits.MAX_TOTAL_MATCH_DOMAINS
            ) {
                droppedRules++
                // A dropped rule loses its exact directives; its domains are
                // barred so no broader survivor silently takes over there.
                // Overflow rejects the catalog rather than truncating.
                addWithheld(withheld, candidate.matchDomains.toSet())
                continue
            }
            totalDomains += candidate.matchDomains.size
            val request = candidate.request
            val html = candidate.html
            val notes = candidate.notes.finish()
            val status = if (notes.isEmpty() && (request.hasEffect() || html.hasAppliedEffect())) {
                RuleStatus.FULL
            } else {
                RuleStatus.PARTIAL
            }
            rules.add(
                SiteRule(
                    siteKey = candidate.siteKey,
                    matchDomains = candidate.matchDomains.toSet(),
                    excludedDomains = candidate.excludedDomains,
                    scope = candidate.scope,
                    request = request,
                    html = html,
                    status = status,
                    notes = notes,
                ),
            )
        }
        if (droppedRules > 0) {
            skipped.add(
                SkippedSite(
                    "(catalog bound)",
                    "$droppedRules rules dropped: catalog is full; their match domains are barred",
                ),
            )
        }
        val finalSkipped = if (skipped.size > SiteCatalogLimits.MAX_SKIPPED_RECORDS) {
            val kept = skipped.take(SiteCatalogLimits.MAX_SKIPPED_RECORDS - 1)
            kept + SkippedSite("(catalog bound)", "more skipped entries dropped (${skipped.size} total)")
        } else {
            skipped.toList()
        }
        // Barriers survive only when no surviving rule still claims them
        // (a valid more-specific replacement wins over the barrier level by
        // matching first; exact duplicates would be ambiguous).
        val claimed = HashSet<String>()
        for (rule in rules) claimed.addAll(rule.matchDomains)
        val barriers = withheld.filter { it !in claimed }.toSet()
        return SiteCatalog(
            meta = SiteCatalogMeta(version, sourceSha256, sourceUrl, ATTRIBUTION, license),
            rules = rules,
            skipped = finalSkipped,
            withheldDomains = barriers,
        )
    }
}
