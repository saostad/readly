package app.readly.input.sites

import app.readly.input.nyt.NytPolicy
import app.readly.input.nyt.NytPolicyException
import app.readly.input.nyt.NytPolicyJson
import app.readly.input.nyt.NytPolicyStorage
import app.readly.input.nyt.NytStatusJson

/**
 * Immutable per-reading view of the shared website rules: the current
 * catalog plus, only when it is newer than the catalog, the validated legacy
 * NYT policy kept as an explicit override. Versions are never merged: reads
 * report the catalog version, and the legacy version only where the override
 * actually supplied the request headers.
 */
data class SiteReadingSnapshot(
    val catalog: SiteCatalog,
    /** Newer validated legacy NYT policy, or null when the catalog covers NYT. */
    val legacyNyt: NytPolicy?,
) {
    /** Catalog version for notices and settings. */
    fun version(): String = catalog.meta.version
}

/**
 * Atomic last-working shared-catalog store with a daily updater, over the
 * one proven [VersionedRuleStore] engine (no second updater, no forked
 * logic). Production cache lives in its own app-private directory
 * (`site_catalog.json`/`site_status.json`); the legacy `nyt/` directory is
 * read migration-only and never written or removed.
 *
 * Usability gate: a downloaded catalog is rejected (keeping the
 * last-working rules with an explicit error) when it is empty, has no usable
 * request or cleanup directives, or is missing/withholding the owner-tested
 * NYT rule. The same gate screens the startup cache (a rejected cache falls
 * back to bundled with a notice) and the bundled data itself (a gated bundle
 * is a fatal shipping bug). The NYT gate requires `nytimes.com` and
 * `www.nytimes.com` to match with [HostScope.APEX_AND_WWW] and a User-Agent;
 * any valid supported UA passes (no hardcoded forever-UA), so genuine
 * upstream UA updates still apply.
 *
 * Legacy migration: a newer validated legacy NYT policy is kept as a
 * separate explicit override until a compatible catalog at or above its
 * version replaces it (equal/older legacy never overrides). The legacy
 * check throttle (last attempt time and error) carries over when no new
 * status file exists, even with no legacy policy; legacy successes are not
 * relabeled as catalog successes. Corrupt/unreadable legacy inputs yield a
 * clear notice and the shared catalog stays usable; missing legacy inputs
 * (fresh installs) are silent.
 *
 * Threading: same contract as the engine — [checkForUpdates] does network
 * and disk I/O, call it off the main thread; [snapshot] never blocks.
 */
class SiteCatalogStore(
    bundledJson: String,
    storage: JsonRuleStorage,
    private val legacyStorage: NytPolicyStorage,
    download: (String) -> ByteArray,
    clock: () -> Long = System::currentTimeMillis,
    private val onWarn: (String) -> Unit = { android.util.Log.w("Readly", it) },
) {
    companion object {
        /** Minimum gap between automatic update attempts, including failures. */
        const val AUTO_CHECK_INTERVAL_MS = VersionedRuleStore.AUTO_CHECK_INTERVAL_MS

        const val POLICY_NAME = "site_catalog.json"
        const val STATUS_NAME = "site_status.json"

        internal val LABELS = StoreLabels(
            bundledWhat = "Bundled site catalog",
            cachedNoun = "Cached site catalog",
            cachedWhat = "Cached site catalog",
            bundledNoun = "bundled catalog",
            artifactNoun = "catalog",
            statusNoun = "Cached site status",
            updateNoun = "Site rule update",
            errorPrefix = "Site rule",
            threadName = "readly-sites-update",
        )
    }

    private val core = VersionedRuleStore(
        bundledJson,
        storage,
        download,
        SiteCatalog.OFFICIAL_UPDATE_URL,
        LABELS,
        decodePolicy = { json, what -> SiteCatalogJson.decode(json, what) },
        encodePolicy = SiteCatalogJson::encode,
        versionOf = { it.meta.version },
        compareVersions = SiteVersions::compareVersions,
        parseUpdate = { bytes, sha, url -> SitesImporter.importZip(bytes, sha, url) },
        isPolicyError = { it is SiteCatalogException },
        sameContent = { candidate, current ->
            candidate.rules == current.rules && candidate.withheldDomains == current.withheldDomains
        },
        validateCandidate = ::validateCandidate,
        migratedThrottle = ::migrateThrottle,
        clock = clock,
        onWarn = onWarn,
    )

    /**
     * Validated legacy NYT policy, loaded once read-only (the legacy writer
     * is gone, so these files never change). Null when missing, corrupt, or
     * unreadable; corruption details go to [startupNotice], never silent.
     */
    private val legacyPolicy: NytPolicy?

    /**
     * Non-null when cache/status/legacy storage degraded at startup; names
     * the real error. Null on a clean start.
     */
    val startupNotice: String?

    init {
        val (legacy, legacyNotice) = loadLegacyPolicy()
        legacyPolicy = legacy
        startupNotice = when {
            core.startupNotice != null && legacyNotice != null ->
                "${core.startupNotice}; $legacyNotice"
            else -> core.startupNotice ?: legacyNotice
        }
        if (legacyNotice != null) onWarn(legacyNotice)
    }

    /** The current complete catalog; never partial update state. Never blocks on updates. */
    fun policy(): SiteCatalog = core.policy()

    /** The current update status for the settings UI. Never blocks on updates. */
    fun status(): RuleUpdateStatus = core.status()

    /**
     * The legacy NYT override in force: the validated legacy policy, but
     * only while its version is strictly newer than the current catalog.
     * Null once a compatible catalog at or above its version replaces it.
     */
    fun legacyOverride(): NytPolicy? = legacyOverrideFor(policy())

    /**
     * Override decision against one captured catalog: the validated legacy
     * policy while strictly newer than [catalog], else null. Pure boundary
     * for tests; [snapshot] captures the catalog once so an update landing
     * mid-read cannot pair an old catalog with a cleared override.
     */
    internal fun legacyOverrideFor(catalog: SiteCatalog): NytPolicy? {
        val legacy = legacyPolicy ?: return null
        return if (SiteVersions.compareVersions(legacy.sourceVersion, catalog.meta.version) > 0) {
            legacy
        } else {
            null
        }
    }

    /** One immutable per-reading snapshot: catalog plus any legacy override. Never blocks. */
    fun snapshot(): SiteReadingSnapshot {
        val current = policy()
        return SiteReadingSnapshot(current, legacyOverrideFor(current))
    }

    /**
     * Runs one update attempt synchronously (network + disk; call off the
     * main thread). [manual] bypasses the 24-hour auto throttle.
     */
    fun checkForUpdates(manual: Boolean = false): RuleUpdateResult = core.checkForUpdates(manual)

    /**
     * Starts [checkForUpdates] on a daemon thread and delivers the result to
     * [onDone] on that same thread.
     */
    fun startUpdateCheck(manual: Boolean = false, onDone: (RuleUpdateResult) -> Unit = {}) =
        core.startUpdateCheck(manual, onDone)

    /**
     * Rejection reason for an unusable catalog, or null when it may proceed
     * to version comparison. Empty, directive-less, and NYT-missing catalogs
     * never replace working rules — as downloads, as the startup cache, or
     * as the bundled data. Reasons are context-free: callers add whether the
     * catalog was a download, a cache, or the bundle.
     */
    private fun validateCandidate(candidate: SiteCatalog): String? {
        if (candidate.rules.isEmpty()) {
            return "catalog has no rules"
        }
        if (candidate.rules.none { it.hasAppliedDirectives() }) {
            return "catalog has no usable request or cleanup directives"
        }
        val apex = candidate.match("nytimes.com")
        val www = candidate.match("www.nytimes.com")
        if (apex == null || www == null) {
            return "catalog withholds the NYT rule " +
                "(nytimes.com or www.nytimes.com did not match)"
        }
        if (apex.rule.scope != HostScope.APEX_AND_WWW || www.rule.scope != HostScope.APEX_AND_WWW) {
            return "catalog broadens the NYT host scope"
        }
        if (apex.rule.request.userAgent == null || www.rule.request.userAgent == null) {
            return "catalog NYT rule has no User-Agent"
        }
        return null
    }

    /**
     * Legacy throttle for first-run migration, consulted only when no new
     * status file exists. Carries the last attempt time and error (so a
     * failed first check still throttles after restart) even when no legacy
     * policy exists. Missing legacy status starts clean and silent; degraded
     * legacy status yields a clear notice with defaults.
     */
    private fun migrateThrottle(): MigratedThrottle? {
        val json = try {
            legacyStorage.readStatusJson()
        } catch (e: Exception) {
            return MigratedThrottle(
                0L,
                null,
                "Legacy NYT status unreadable, using defaults: ${e.javaClass.name}: ${e.message}",
            )
        } ?: return null
        return try {
            val parsed = NytStatusJson.decode(json)
            MigratedThrottle(parsed.lastCheckMs, parsed.lastError, null)
        } catch (e: NytPolicyException) {
            MigratedThrottle(0L, null, "Legacy NYT status corrupt, using defaults: ${e.message}")
        }
    }

    /**
     * Reads the legacy NYT policy once, read-only. The legacy directory is
     * never written or removed by this store.
     */
    private fun loadLegacyPolicy(): Pair<NytPolicy?, String?> {
        val json = try {
            legacyStorage.readPolicyJson()
        } catch (e: Exception) {
            return Pair(
                null,
                "Legacy NYT rule unreadable, using shared catalog ${policy().meta.version}: " +
                    "${e.javaClass.name}: ${e.message}",
            )
        } ?: return Pair(null, null)
        return try {
            Pair(NytPolicyJson.decode(json, "Legacy NYT rule"), null)
        } catch (e: NytPolicyException) {
            Pair(
                null,
                "Legacy NYT rule corrupt, using shared catalog ${policy().meta.version}: ${e.message}",
            )
        }
    }
}
