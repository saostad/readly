package app.readly.input.nyt

import app.readly.input.sites.CoordinatorLabels
import app.readly.input.sites.RuleUiState
import app.readly.input.sites.RuleUpdateCoordinator
import app.readly.input.sites.RuleUpdateResult
import app.readly.input.sites.RuleUpdateStatus
import app.readly.input.sites.StoreOps

/** Legacy alias for the shared coordinator UI state. */
typealias NytUiState = RuleUiState

/** [StoreOps] binding the generic coordinator to [NytPolicyStore]. */
object NytStoreOps : StoreOps<NytPolicyStore, NytPolicy, NytUpdateResult> {
    override fun checkForUpdates(store: NytPolicyStore, manual: Boolean): NytUpdateResult =
        store.checkForUpdates(manual)

    override fun status(store: NytPolicyStore): RuleUpdateStatus = store.status()

    override fun policy(store: NytPolicyStore): NytPolicy = store.policy()

    override fun startupNotice(store: NytPolicyStore): String? = store.startupNotice

    override fun isThrottled(result: NytUpdateResult): Boolean = result is NytUpdateResult.Throttled
}

/**
 * App-owned NYT rule lifecycle over the one proven coordinator engine.
 * Public API and every message are unchanged; production now runs the
 * shared catalog coordinator instead, and this class remains as the tested
 * legacy wrapper. Pure JVM core — Android wiring arrives as lambdas, so
 * host tests drive fakes without touching Android stubs.
 *
 * Threading: [ensureStarted], [onNytRead], [checkUpdates], [uiState] and
 * [isChecking] return immediately and are main-safe; init, disk and network
 * run only on the background executor (or inline in [policyForReading],
 * which callers use off the main thread).
 */
class NytUpdateCoordinator(
    storeFactory: () -> NytPolicyStore,
    isAutoUpdateEnabled: () -> Boolean,
    runBackground: (Runnable) -> Unit,
    onWarn: (String) -> Unit = { android.util.Log.w("Readly", it) },
) : RuleUpdateCoordinator<NytPolicyStore, NytPolicy, NytUpdateResult>(
    storeFactory,
    isAutoUpdateEnabled,
    runBackground,
    NytStoreOps,
    CoordinatorLabels("NYT rule update", "NYT reading fixes", "rule"),
    onWarn,
) {
    /** Runs one automatic check on the background executor (call after an NYT read). */
    fun onNytRead() {
        onRead()
    }

    companion object {
        /**
         * Notice for a successful NYT reading that applied [version]: the
         * base line plus any startup/cache/update warnings, each kept whole
         * so the real error stays visible. Blank extras are skipped.
         */
        fun readingNotice(
            version: String,
            startupNotice: String?,
            warning: String?,
            lastError: String?,
        ): String {
            val parts = mutableListOf("Using New York Times reading fixes (rules $version).")
            if (!startupNotice.isNullOrBlank()) parts.add(startupNotice)
            if (!warning.isNullOrBlank()) parts.add(warning)
            if (!lastError.isNullOrBlank()) parts.add(lastError)
            return parts.joinToString(" ")
        }
    }
}
