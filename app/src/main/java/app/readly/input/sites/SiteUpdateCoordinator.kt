package app.readly.input.sites

/**
 * Settings state for the shared website rules: the base lifecycle state plus
 * the computed catalog summary and any legacy override in force. Produced by
 * [SiteUpdateCoordinator.siteUiState], which never touches disk or network.
 */
data class SiteUiState(
    val base: RuleUiState,
    /**
     * Computed import summary (`"913 domains imported; 57 request rules, 3
     * cleanup rules."`), null until the store is ready. Counts, never a
     * supported-site claim.
     */
    val summary: String?,
    /** Newer legacy NYT version in force as an override, or null. */
    val legacyOverrideVersion: String?,
)

/** [StoreOps] binding the generic coordinator to [SiteCatalogStore]. */
object SiteStoreOps : StoreOps<SiteCatalogStore, SiteReadingSnapshot, RuleUpdateResult> {
    override fun checkForUpdates(store: SiteCatalogStore, manual: Boolean): RuleUpdateResult =
        store.checkForUpdates(manual)

    override fun status(store: SiteCatalogStore): RuleUpdateStatus = store.status()

    override fun policy(store: SiteCatalogStore): SiteReadingSnapshot = store.snapshot()

    override fun startupNotice(store: SiteCatalogStore): String? = store.startupNotice

    override fun isThrottled(result: RuleUpdateResult): Boolean = result is RuleUpdateResult.Throttled
}

/**
 * App-owned shared-catalog lifecycle over the one proven coordinator engine:
 * initializes the [SiteCatalogStore] once (bundled asset plus validated
 * cache, with read-only legacy NYT migration) and runs automatic and manual
 * update checks on an injected background executor. Production runs exactly
 * one of these; the NYT coordinator remains only as the tested legacy
 * wrapper and is never initialized in production.
 *
 * Threading: same contract as [RuleUpdateCoordinator] — [ensureStarted],
 * [onRead], [checkUpdates], [uiState], [siteUiState] and [isChecking] are
 * main-safe; [snapshotForReading] runs init inline and must be called off
 * the main thread on first use.
 */
class SiteUpdateCoordinator(
    storeFactory: () -> SiteCatalogStore,
    isAutoUpdateEnabled: () -> Boolean,
    runBackground: (Runnable) -> Unit,
    onWarn: (String) -> Unit = { android.util.Log.w("Readly", it) },
) : RuleUpdateCoordinator<SiteCatalogStore, SiteReadingSnapshot, RuleUpdateResult>(
    storeFactory,
    isAutoUpdateEnabled,
    runBackground,
    SiteStoreOps,
    CoordinatorLabels("Site rule update", "Site reading fixes", "catalog"),
    onWarn,
) {
    /**
     * The current per-reading snapshot, initializing inline when the
     * background task has not finished. Call off the main thread on first
     * use; never runs network. Null when the bundled catalog itself is
     * missing or invalid — callers then read with the default opener and
     * surface [uiState]'s startup error.
     */
    fun snapshotForReading(): SiteReadingSnapshot? = policyForReading()

    /**
     * Immediate settings state; main-safe, never blocks on I/O or init.
     * [SiteUiState.summary] is present once the store is ready.
     */
    fun siteUiState(): SiteUiState {
        val base = uiState()
        val snapshot = storeIfReady()?.snapshot()
        return SiteUiState(
            base = base,
            summary = snapshot?.let { catalogSummary(it.catalog) },
            legacyOverrideVersion = snapshot?.legacyNyt?.sourceVersion,
        )
    }

    companion object {
        /**
         * Computed import summary for settings: match-domain, request-rule
         * and cleanup-rule counts. Import totals, never a supported-site
         * claim.
         */
        fun catalogSummary(catalog: SiteCatalog): String {
            val domains = catalog.rules.sumOf { it.matchDomains.size }
            val request = catalog.rules.count { it.request.hasEffect() }
            val cleanup = catalog.rules.count { it.html.cleanup.isNotEmpty() }
            return "$domains domains imported; $request request rules, $cleanup cleanup rules."
        }

        /**
         * Notice for a reading that applied shared fixes to [host] under
         * [version]: the base line names only applied directives, plus a
         * concise partial-compatibility line when the applied rule is
         * partial, the legacy override version where it supplied the read,
         * and any startup/cache/update warnings, each kept whole so the real
         * error stays visible. Blank extras are skipped. Callers show this
         * only when headers were sent or cleanup matched — never for a
         * no-op rule.
         */
        fun readingNotice(
            host: String,
            version: String,
            partial: Boolean,
            legacyVersion: String?,
            startupNotice: String?,
            warning: String?,
            lastError: String?,
        ): String {
            val parts = mutableListOf("Using reading fixes for $host (rules $version).")
            if (legacyVersion != null) {
                parts.add("New York Times fixes use legacy rules $legacyVersion.")
            }
            if (partial) {
                parts.add("Compatible parts only; some site features need browser-specific fixes.")
            }
            if (!startupNotice.isNullOrBlank()) parts.add(startupNotice)
            if (!warning.isNullOrBlank()) parts.add(warning)
            if (!lastError.isNullOrBlank()) parts.add(lastError)
            return parts.joinToString(" ")
        }
    }
}
