package app.readly.input.sites

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Strict JSON codec for [SiteCatalog]: our own declarative rule format for
 * the bundled asset and the update cache, never the upstream extension
 * code. Unknown fields are ignored so old readers tolerate new writers,
 * but every authority-bearing field (domains, headers, selectors, scopes,
 * known-label values) is validated exactly as strictly as the importer,
 * so a tampered cache cannot smuggle broader authority than an import
 * could produce.
 */
object SiteCatalogJson {
    /**
     * Schema 2 adds the required cleanup `groupId` (one upstream `cond`
     * keeps one matched-node set at runtime). Schema-1 data is rejected as
     * unsupported: without grouping the executor would re-select mutated
     * selectors and silently drop sibling actions.
     */
    const val SCHEMA = 2

    private val SHA_RE = Regex("[0-9a-f]{64}")

    fun encode(catalog: SiteCatalog): String {
        val o = JSONObject()
        o.put("schema", SCHEMA)
        val meta = JSONObject()
        meta.put("version", catalog.meta.version)
        meta.put("sourceSha256", catalog.meta.sourceSha256)
        meta.put("sourceUrl", catalog.meta.sourceUrl)
        meta.put("attribution", catalog.meta.attribution)
        meta.put("license", catalog.meta.license)
        o.put("meta", meta)
        val rules = JSONArray()
        for (rule in catalog.rules) rules.put(encodeRule(rule))
        o.put("rules", rules)
        val skipped = JSONArray()
        for (s in catalog.skipped) {
            skipped.put(JSONObject().put("siteKey", s.siteKey).put("reason", s.reason))
        }
        o.put("skipped", skipped)
        o.put("withheldDomains", JSONArray(catalog.withheldDomains.sorted()))
        return o.toString(2)
    }

    private fun encodeRule(rule: SiteRule): JSONObject {
        val o = JSONObject()
        o.put("siteKey", rule.siteKey)
        o.put("matchDomains", JSONArray(rule.matchDomains.sorted()))
        o.put("excludedDomains", JSONArray(rule.excludedDomains.sorted()))
        o.put("scope", rule.scope.name)
        val req = JSONObject()
        req.put("userAgent", rule.request.userAgent ?: JSONObject.NULL)
        req.put("userAgentSource", rule.request.userAgentSource ?: JSONObject.NULL)
        req.put("referer", rule.request.referer ?: JSONObject.NULL)
        req.put("refererSource", rule.request.refererSource ?: JSONObject.NULL)
        val extras = JSONObject()
        for ((k, v) in rule.request.extraHeaders) extras.put(k, v)
        req.put("extraHeaders", extras)
        o.put("request", req)
        val html = JSONObject()
        val cleanup = JSONArray()
        for (op in rule.html.cleanup) cleanup.put(encodeOp(op))
        html.put("cleanup", cleanup)
        html.put(
            "ldJson",
            rule.html.ldJson?.let {
                JSONObject()
                    .put("kind", it.kind)
                    .put("paywallSelector", it.paywallSelector)
                    .put("articleSelector", it.articleSelector)
                    .put("extra", JSONArray(it.extra))
            } ?: JSONObject.NULL,
        )
        html.put(
            "ampRedirect",
            rule.html.ampRedirect?.let {
                JSONObject()
                    .put("paywallSelector", it.paywallSelector)
                    .put("ampUrlTemplate", it.ampUrlTemplate ?: JSONObject.NULL)
            } ?: JSONObject.NULL,
        )
        o.put("html", html)
        o.put("status", rule.status.name)
        o.put("notes", JSONArray(rule.notes))
        return o
    }

    private fun encodeOp(op: CleanupOp): JSONObject {
        val o = JSONObject()
        o.put("kind", op.kind.name)
        o.put("selector", op.selector)
        o.put("names", JSONArray(op.names))
        o.put("groupId", op.groupId)
        val children = JSONArray()
        for (child in op.children) children.put(encodeOp(child))
        o.put("children", children)
        return o
    }

    @Throws(SiteCatalogException::class)
    fun decode(json: String, what: String = "Site catalog"): SiteCatalog {
        val o = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw SiteCatalogException(
                "$what JSON is invalid: ${e.javaClass.name}: ${e.message}",
                e,
            )
        }
        fun fail(detail: String): Nothing = throw SiteCatalogException("$what JSON is invalid: $detail")
        val schema = o.opt("schema")
        val schemaOk = (schema is Int || schema is Long) && schema.toLong() == SCHEMA.toLong()
        if (!schemaOk) fail("unsupported schema ${UpdateArchive.describeJsonValue(schema)}")

        val metaObj = o.opt("meta")
        if (metaObj !is JSONObject) fail("\"meta\" must be an object")
        val version = metaObj.opt("version")
        if (version !is String || !SiteVersions.isValidVersion(version)) {
            fail("invalid \"version\" ${UpdateArchive.describeJsonValue(version)}")
        }
        val sha = metaObj.opt("sourceSha256")
        if (sha !is String || (sha.isNotEmpty() && !SHA_RE.matches(sha))) {
            fail("invalid \"sourceSha256\": expected \"\" or 64 lowercase hex chars")
        }
        val url = metaObj.opt("sourceUrl")
        if (url !is String || url != SiteCatalog.OFFICIAL_UPDATE_URL) {
            fail("unexpected \"sourceUrl\" ${UpdateArchive.describeJsonValue(url)}")
        }
        val attribution = metaObj.opt("attribution")
        if (attribution !is String || attribution.isBlank()) fail("\"attribution\" is blank")
        if (attribution.length > SiteCatalogLimits.MAX_ATTRIBUTION_LENGTH) {
            fail("\"attribution\" is over ${SiteCatalogLimits.MAX_ATTRIBUTION_LENGTH} chars")
        }
        val license = metaObj.opt("license")
        if (license !is String || license.isBlank()) fail("\"license\" is blank")
        if (!license.startsWith("MIT License")) fail("\"license\" is not the upstream MIT text")
        if (license.length > SiteCatalogLimits.MAX_LICENSE_LENGTH) {
            fail("\"license\" is over ${SiteCatalogLimits.MAX_LICENSE_LENGTH} chars")
        }

        val rulesJson = o.opt("rules")
        if (rulesJson !is JSONArray) fail("\"rules\" must be an array")
        if (rulesJson.length() > SiteCatalogLimits.MAX_RULES) {
            fail("\"rules\" has more than ${SiteCatalogLimits.MAX_RULES} entries")
        }
        val rules = ArrayList<SiteRule>(rulesJson.length())
        val seenDomains = HashSet<String>()
        var totalDomains = 0
        for (i in 0 until rulesJson.length()) {
            val raw = rulesJson.opt(i)
            if (raw !is JSONObject) fail("\"rules\" item $i must be an object")
            rules.add(decodeRule(raw, i, what, seenDomains, { fail(it) }))
            totalDomains += rules.last().matchDomains.size
            if (totalDomains > SiteCatalogLimits.MAX_TOTAL_MATCH_DOMAINS) {
                fail("catalog holds more than ${SiteCatalogLimits.MAX_TOTAL_MATCH_DOMAINS} match domains")
            }
        }

        val skippedJson = o.opt("skipped")
        if (skippedJson != null && skippedJson != JSONObject.NULL && skippedJson !is JSONArray) {
            fail("\"skipped\" must be an array")
        }
        val skipped = mutableListOf<SkippedSite>()
        if (skippedJson is JSONArray) {
            if (skippedJson.length() > SiteCatalogLimits.MAX_SKIPPED_RECORDS) {
                fail("\"skipped\" has more than ${SiteCatalogLimits.MAX_SKIPPED_RECORDS} records")
            }
            for (i in 0 until skippedJson.length()) {
                val raw = skippedJson.opt(i)
                if (raw !is JSONObject) fail("\"skipped\" item $i must be an object")
                val key = checkSiteKey(raw.opt("siteKey"), "\"skipped\" item $i \"siteKey\"", { fail(it) })
                val reason = checkNote(raw.opt("reason"), "\"skipped\" item $i \"reason\"", { fail(it) })
                skipped.add(SkippedSite(key, reason))
            }
        }

        val withheldJson = o.opt("withheldDomains")
        val withheld = LinkedHashSet<String>()
        if (withheldJson != null && withheldJson != JSONObject.NULL) {
            if (withheldJson !is JSONArray) fail("\"withheldDomains\" must be an array")
            if (withheldJson.length() > SiteCatalogLimits.MAX_WITHHELD_DOMAINS) {
                fail("\"withheldDomains\" has more than ${SiteCatalogLimits.MAX_WITHHELD_DOMAINS} domains")
            }
            for (i in 0 until withheldJson.length()) {
                val raw = withheldJson.opt(i)
                if (raw !is String) fail("\"withheldDomains\" item $i must be a string")
                val reason = SiteDomains.invalidReason(raw)
                if (reason != null) fail("\"withheldDomains\" item $i $reason")
                val normalized = SiteDomains.normalizeRuleDomain(raw)!!
                if (!withheld.add(normalized)) fail("duplicate withheld domain \"$normalized\"")
                if (normalized in seenDomains) {
                    fail("withheld domain \"$normalized\" is also claimed (ambiguous)")
                }
            }
        }

        return SiteCatalog(
            meta = SiteCatalogMeta(version, sha, url, attribution, license),
            rules = rules,
            skipped = skipped,
            withheldDomains = withheld,
        )
    }

    private fun decodeRule(
        o: JSONObject,
        index: Int,
        what: String,
        seenDomains: MutableSet<String>,
        fail: (String) -> Nothing,
    ): SiteRule {
        fun fieldFail(detail: String): Nothing = fail("\"rules\" item $index $detail")
        val siteKey = checkSiteKey(o.opt("siteKey"), "\"siteKey\"", { fieldFail(it) })

        val matchJson = o.opt("matchDomains")
        if (matchJson !is JSONArray) fieldFail("\"matchDomains\" must be an array")
        if (matchJson.length() == 0) fieldFail("\"matchDomains\" must not be empty")
        if (matchJson.length() > SiteCatalogLimits.MAX_MATCH_DOMAINS_PER_RULE) {
            fieldFail("\"matchDomains\" has more than ${SiteCatalogLimits.MAX_MATCH_DOMAINS_PER_RULE} domains")
        }
        val matchDomains = LinkedHashSet<String>()
        for (i in 0 until matchJson.length()) {
            val raw = matchJson.opt(i)
            if (raw !is String) fieldFail("\"matchDomains\" item $i must be a string")
            val reason = SiteDomains.invalidReason(raw)
            if (reason != null) fieldFail("\"matchDomains\" item $i $reason")
            val normalized = SiteDomains.normalizeRuleDomain(raw)!!
            if (!matchDomains.add(normalized)) fieldFail("duplicate match domain \"$normalized\"")
            if (!seenDomains.add(normalized)) {
                fieldFail("match domain \"$normalized\" is already claimed (ambiguous)")
            }
        }

        val excluded = decodeDomainSet(o.opt("excludedDomains"), "\"excludedDomains\"", { fieldFail(it) })

        val scopeRaw = o.opt("scope")
        val scope = try {
            if (scopeRaw !is String) fieldFail("\"scope\" must be a string")
            HostScope.valueOf(scopeRaw)
        } catch (e: IllegalArgumentException) {
            fieldFail("unknown \"scope\" ${UpdateArchive.describeJsonValue(scopeRaw)}")
        }
        // The compat override is data-visible: a cached rule covering an
        // override domain must carry at least the override's restriction.
        val requiredScope = SiteCompatPolicy.scopeFor(matchDomains)
        if (requiredScope == HostScope.APEX_AND_WWW && scope != HostScope.APEX_AND_WWW) {
            fieldFail("\"scope\" must be APEX_AND_WWW for a compatibility domain")
        }

        val reqJson = o.opt("request")
        if (reqJson !is JSONObject) fieldFail("\"request\" must be an object")
        val request = decodeRequest(reqJson, { fieldFail(it) })

        val htmlJson = o.opt("html")
        if (htmlJson !is JSONObject) fieldFail("\"html\" must be an object")
        val html = decodeHtml(htmlJson, { fieldFail(it) })

        val statusRaw = o.opt("status")
        val status = try {
            if (statusRaw !is String) fieldFail("\"status\" must be a string")
            RuleStatus.valueOf(statusRaw)
        } catch (e: IllegalArgumentException) {
            fieldFail("unknown \"status\" ${UpdateArchive.describeJsonValue(statusRaw)}")
        }

        val notesJson = o.opt("notes")
        if (notesJson !is JSONArray) fieldFail("\"notes\" must be an array")
        if (notesJson.length() > SiteCatalogLimits.MAX_NOTES_PER_RULE) {
            fieldFail("\"notes\" has more than ${SiteCatalogLimits.MAX_NOTES_PER_RULE} entries")
        }
        val notes = mutableListOf<String>()
        for (i in 0 until notesJson.length()) {
            notes.add(checkNote(notesJson.opt(i), "\"notes\" item $i", { fieldFail(it) }))
        }

        return SiteRule(siteKey, matchDomains, excluded, scope, request, html, status, notes)
    }

    private fun decodeDomainSet(
        raw: Any?,
        field: String,
        fieldFail: (String) -> Nothing,
    ): Set<String> {
        if (raw !is JSONArray) fieldFail("$field must be an array")
        if (raw.length() > SiteCatalogLimits.MAX_EXCLUDED_PER_RULE) {
            fieldFail("$field has more than ${SiteCatalogLimits.MAX_EXCLUDED_PER_RULE} domains")
        }
        val out = LinkedHashSet<String>()
        for (i in 0 until raw.length()) {
            val item = raw.opt(i)
            if (item !is String) fieldFail("$field item $i must be a string")
            val reason = SiteDomains.invalidReason(item)
            if (reason != null) fieldFail("$field item $i $reason")
            if (!out.add(SiteDomains.normalizeRuleDomain(item)!!)) fieldFail("$field has duplicate \"${item}\"")
        }
        return out
    }

    private fun decodeRequest(o: JSONObject, fieldFail: (String) -> Nothing): SiteRequestPolicy {
        val userAgent = when (val v = o.opt("userAgent")) {
            null, JSONObject.NULL -> null
            is String -> {
                checkHeaderValue(v, "\"userAgent\"", { fieldFail(it) })
                v
            }
            else -> fieldFail("\"userAgent\" must be a string or null")
        }
        val userAgentSource = when (val v = o.opt("userAgentSource")) {
            null, JSONObject.NULL -> null
            is String -> v
            else -> fieldFail("\"userAgentSource\" must be a string or null")
        }
        if ((userAgent == null) != (userAgentSource == null)) {
            fieldFail("\"userAgent\" and \"userAgentSource\" must both be set or both be null")
        }
        if (userAgent != null && userAgentSource != null) {
            val bot = KnownBot.byId(userAgentSource)
            if (bot != null) {
                if (userAgent != bot.userAgent) {
                    fieldFail("\"userAgent\" does not match the known \"$userAgentSource\" value")
                }
            } else if (userAgentSource != "custom") {
                fieldFail("unknown \"userAgentSource\" \"$userAgentSource\"")
            }
        }
        val referer = when (val v = o.opt("referer")) {
            null, JSONObject.NULL -> null
            is String -> {
                checkHeaderValue(v, "\"referer\"", { fieldFail(it) })
                v
            }
            else -> fieldFail("\"referer\" must be a string or null")
        }
        val refererSource = when (val v = o.opt("refererSource")) {
            null, JSONObject.NULL -> null
            is String -> v
            else -> fieldFail("\"refererSource\" must be a string or null")
        }
        if ((referer == null) != (refererSource == null)) {
            fieldFail("\"referer\" and \"refererSource\" must both be set or both be null")
        }
        if (referer != null && refererSource != null) {
            val preset = RefererPreset.byId(refererSource)
            when {
                preset != null && referer != preset.url -> {
                    fieldFail("\"referer\" does not match the known \"$refererSource\" value")
                }
                refererSource == "googlebot" && referer != RefererPreset.GOOGLE.url -> {
                    fieldFail("\"referer\" does not match the known googlebot value")
                }
                refererSource == "custom" -> {
                    val reason = SiteRequests.invalidRefererReason(referer, SiteHeaders.MAX_VALUE_LENGTH)
                    if (reason != null) fieldFail("\"referer\" $reason")
                }
                preset == null && refererSource != "googlebot" && refererSource != "custom" -> {
                    fieldFail("unknown \"refererSource\" \"$refererSource\"")
                }
            }
        }
        val extrasJson = o.opt("extraHeaders")
        if (extrasJson !is JSONObject) fieldFail("\"extraHeaders\" must be an object")
        if (extrasJson.length() > SiteCatalogLimits.MAX_EXTRA_HEADERS) {
            fieldFail("\"extraHeaders\" has more than ${SiteCatalogLimits.MAX_EXTRA_HEADERS} entries")
        }
        val extras = LinkedHashMap<String, String>()
        val seenNames = HashSet<String>()
        for (key in extrasJson.keys()) {
            if (!SiteHeaders.isValidName(key)) fieldFail("\"extraHeaders\" has invalid name \"$key\"")
            if (key.lowercase() in SiteHeaders.FORBIDDEN_REQUEST_NAMES) {
                fieldFail("\"extraHeaders\" must not set \"$key\"")
            }
            if (!seenNames.add(key.lowercase())) fieldFail("\"extraHeaders\" has duplicate name \"$key\"")
            val value = extrasJson.opt(key)
            if (value !is String) fieldFail("\"extraHeaders\" \"$key\" must be a string")
            checkExtraValue(value, key, fieldFail)
            extras[key] = value
        }
        return SiteRequestPolicy(userAgent, userAgentSource, referer, refererSource, extras)
    }

    private fun checkHeaderValue(v: String, field: String, fieldFail: (String) -> Nothing) {
        if (v.isBlank()) fieldFail("$field is blank")
        if (v.any { it == '\n' || it == '\r' }) fieldFail("$field must be a single line")
        val bad = SiteHeaders.findInvalidChar(v)
        if (bad != null) {
            fieldFail("$field has invalid header character ${SiteHeaders.describeChar(bad)}")
        }
        if (v.length > SiteHeaders.MAX_VALUE_LENGTH) {
            fieldFail("$field is over ${SiteHeaders.MAX_VALUE_LENGTH} chars")
        }
    }

    private fun checkExtraValue(v: String, name: String, fieldFail: (String) -> Nothing) {
        if (v.isEmpty()) fieldFail("\"extraHeaders\" \"$name\" is empty")
        if (v.first().isWhitespace() || v.last().isWhitespace()) {
            fieldFail("\"extraHeaders\" \"$name\" has leading or trailing whitespace")
        }
        checkHeaderValue(if (v.isBlank()) "x" else v, "\"extraHeaders\" \"$name\"", fieldFail)
        if (v.isBlank()) fieldFail("\"extraHeaders\" \"$name\" is blank")
    }

    private fun decodeHtml(o: JSONObject, fieldFail: (String) -> Nothing): SiteHtmlDirectives {
        val cleanupJson = o.opt("cleanup")
        if (cleanupJson !is JSONArray) fieldFail("\"cleanup\" must be an array")
        val opCount = intArrayOf(0)
        val cleanup = decodeOpList(cleanupJson, "\"cleanup\"", 0, opCount, fieldFail)
        if (opCount[0] > SiteCatalogLimits.MAX_CLEANUP_OPS) {
            fieldFail("\"cleanup\" holds more than ${SiteCatalogLimits.MAX_CLEANUP_OPS} ops")
        }
        val ldJson = when (val v = o.opt("ldJson")) {
            null, JSONObject.NULL -> null
            is JSONObject -> {
                val kind = v.opt("kind")
                if (kind != "ld_json" && kind != "ld_json_url") {
                    fieldFail("\"ldJson\" has unknown kind ${UpdateArchive.describeJsonValue(kind)}")
                }
                checkSelectorField(v.opt("paywallSelector"), "\"ldJson\" \"paywallSelector\"", fieldFail)
                checkSelectorField(v.opt("articleSelector"), "\"ldJson\" \"articleSelector\"", fieldFail)
                val extraJson = v.opt("extra")
                if (extraJson !is JSONArray) fieldFail("\"ldJson\" \"extra\" must be an array")
                if (extraJson.length() > SiteCatalogLimits.MAX_LD_JSON_EXTRA) {
                    fieldFail("\"ldJson\" \"extra\" has more than ${SiteCatalogLimits.MAX_LD_JSON_EXTRA} segments")
                }
                val extra = mutableListOf<String>()
                for (i in 0 until extraJson.length()) {
                    val item = extraJson.opt(i)
                    if (item !is String || item.isEmpty()) {
                        fieldFail("\"ldJson\" \"extra\" item $i must be a non-empty string")
                    }
                    if (item.length > SiteCatalogLimits.MAX_LD_JSON_EXTRA_LENGTH) fieldFail("\"ldJson\" \"extra\" item $i is too long")
                    if (item.any { it == '\n' || it == '\r' }) fieldFail("\"ldJson\" \"extra\" item $i must be a single line")
                    extra.add(item)
                }
                LdJsonRule(
                    kind as String,
                    v.opt("paywallSelector") as String,
                    v.opt("articleSelector") as String,
                    extra,
                )
            }
            else -> fieldFail("\"ldJson\" must be an object or null")
        }
        val ampRedirect = when (val v = o.opt("ampRedirect")) {
            null, JSONObject.NULL -> null
            is JSONObject -> {
                checkSelectorField(v.opt("paywallSelector"), "\"ampRedirect\" \"paywallSelector\"", fieldFail)
                val template = when (val t = v.opt("ampUrlTemplate")) {
                    null, JSONObject.NULL -> null
                    is String -> {
                        checkAmpTemplate(t, fieldFail)
                        t
                    }
                    else -> fieldFail("\"ampRedirect\" \"ampUrlTemplate\" must be a string or null")
                }
                AmpRedirectRule(v.opt("paywallSelector") as String, template)
            }
            else -> fieldFail("\"ampRedirect\" must be an object or null")
        }
        return SiteHtmlDirectives(cleanup, ldJson, ampRedirect)
    }

    /**
     * Decodes one ops list (top-level or one `children` array) and rejects a
     * tampered grouping: ops sharing a `groupId` must also share the
     * selector, so a cache cannot merge unrelated selectors into one
     * matched-node set. Ids are otherwise opaque (the importer may skip
     * values for dropped actions), only required to be non-negative ints.
     */
    private fun decodeOpList(
        json: JSONArray,
        what: String,
        depth: Int,
        opCount: IntArray,
        fieldFail: (String) -> Nothing,
    ): List<CleanupOp> {
        val ops = mutableListOf<CleanupOp>()
        for (i in 0 until json.length()) {
            val raw = json.opt(i)
            if (raw !is JSONObject) fieldFail("$what item $i must be an object")
            ops.add(decodeOp(raw, "$what item $i", depth, opCount, fieldFail))
        }
        val selectorsByGroup = mutableMapOf<Int, String>()
        for (op in ops) {
            val seen = selectorsByGroup.putIfAbsent(op.groupId, op.selector)
            if (seen != null && seen != op.selector) {
                fieldFail("$what reuses groupId ${op.groupId} for different selectors")
            }
        }
        return ops
    }

    private fun decodeOp(
        o: JSONObject,
        what: String,
        depth: Int,
        opCount: IntArray,
        fieldFail: (String) -> Nothing,
    ): CleanupOp {
        if (depth > SiteCatalogLimits.MAX_CLEANUP_DEPTH) {
            fieldFail("$what nests deeper than ${SiteCatalogLimits.MAX_CLEANUP_DEPTH} levels")
        }
        opCount[0]++
        val kindRaw = o.opt("kind")
        val kind = try {
            if (kindRaw !is String) fieldFail("$what \"kind\" must be a string")
            CleanupKind.valueOf(kindRaw)
        } catch (e: IllegalArgumentException) {
            fieldFail("$what has unknown kind ${UpdateArchive.describeJsonValue(kindRaw)}")
        }
        checkSelectorField(o.opt("selector"), "$what \"selector\"", fieldFail)
        val namesJson = o.opt("names")
        if (namesJson !is JSONArray) fieldFail("$what \"names\" must be an array")
        if (namesJson.length() > SiteCatalogLimits.MAX_CLEANUP_NAMES) {
            fieldFail("$what has more than ${SiteCatalogLimits.MAX_CLEANUP_NAMES} names")
        }
        val names = mutableListOf<String>()
        for (i in 0 until namesJson.length()) {
            val item = namesJson.opt(i)
            if (item !is String) fieldFail("$what \"names\" item $i must be a string")
            val ok = when (kind) {
                CleanupKind.REMOVE_ATTRS -> SiteSelectors.isValidAttrName(item)
                CleanupKind.REMOVE_CLASSES -> SiteSelectors.isValidClassName(item)
                CleanupKind.HIDE, CleanupKind.REMOVE -> false
            }
            if (!ok) fieldFail("$what \"names\" item $i is invalid for $kind")
            names.add(item)
        }
        if ((kind == CleanupKind.HIDE || kind == CleanupKind.REMOVE) && names.isNotEmpty()) {
            fieldFail("$what must not carry names for $kind")
        }
        if ((kind == CleanupKind.REMOVE_ATTRS || kind == CleanupKind.REMOVE_CLASSES) && names.isEmpty()) {
            fieldFail("$what needs at least one name for $kind")
        }
        val groupId = when (val g = o.opt("groupId")) {
            is Int -> g
            is Long -> {
                if (g < 0 || g > Int.MAX_VALUE) fieldFail("$what \"groupId\" is out of range")
                g.toInt()
            }
            else -> fieldFail("$what \"groupId\" must be an integer")
        }
        if (groupId < 0) fieldFail("$what \"groupId\" must not be negative")
        val childrenJson = o.opt("children")
        if (childrenJson !is JSONArray) fieldFail("$what \"children\" must be an array")
        val children = decodeOpList(childrenJson, "$what child", depth + 1, opCount, fieldFail)
        return CleanupOp(kind, o.opt("selector") as String, names, groupId, children)
    }

    private fun checkSelectorField(raw: Any?, field: String, fieldFail: (String) -> Nothing) {
        if (raw !is String) fieldFail("$field must be a string")
        val reason = SiteSelectors.invalidSelectorReason(raw, SiteCatalogLimits.MAX_SELECTOR_LENGTH)
        if (reason != null) fieldFail("$field $reason")
    }

    private fun checkAmpTemplate(t: String, fieldFail: (String) -> Nothing) {
        if (t.isBlank()) fieldFail("\"ampRedirect\" \"ampUrlTemplate\" is blank")
        if (t.length > SiteCatalogLimits.MAX_AMP_URL_LENGTH) {
            fieldFail("\"ampRedirect\" \"ampUrlTemplate\" is over ${SiteCatalogLimits.MAX_AMP_URL_LENGTH} chars")
        }
        if (t.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }) {
            fieldFail("\"ampRedirect\" \"ampUrlTemplate\" has whitespace or control characters")
        }
    }

    private fun checkSiteKey(raw: Any?, field: String, fail: (String) -> Nothing): String {
        if (raw !is String || raw.isBlank()) fail("$field is blank")
        if (raw.length > SiteCatalogLimits.MAX_SITE_KEY_LENGTH) {
            fail("$field is over ${SiteCatalogLimits.MAX_SITE_KEY_LENGTH} chars")
        }
        if (raw.any { it == '\n' || it == '\r' }) fail("$field must be a single line")
        return raw
    }

    private fun checkNote(raw: Any?, field: String, fail: (String) -> Nothing): String {
        if (raw !is String || raw.isBlank()) fail("$field is blank")
        if (raw.length > SiteCatalogLimits.MAX_NOTE_LENGTH) {
            fail("$field is over ${SiteCatalogLimits.MAX_NOTE_LENGTH} chars")
        }
        if (raw.any { it == '\n' || it == '\r' }) fail("$field must be a single line")
        return raw
    }
}
