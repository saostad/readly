package app.readly.input.sites

/**
 * Point-in-time UI state for the settings sheet. Produced by
 * [RuleUpdateCoordinator.uiState], which never touches disk or network, so it
 * is safe to call on the main thread.
 */
data class RuleUiState(
    /** True once the rule store initialized (bundled fallback counts). */
    val ready: Boolean,
    /** True while an update check is running on the background executor. */
    val checking: Boolean,
    /** Current rule version, null until [ready]. */
    val version: String? = null,
    /** `"bundled"` or `"cache"`, null until [ready]. */
    val origin: String? = null,
    /** Last attempt time, 0 when never checked. */
    val lastCheckMs: Long = 0,
    /** Full error of the last failed check, null when the last check succeeded. */
    val lastError: String? = null,
    /** Transient status-persistence warning, separate from [lastError]. */
    val warning: String? = null,
    /** Startup cache/status degradation notice, null on a clean start. */
    val startupNotice: String? = null,
    /** Fatal startup failure (missing/invalid bundled rules), null normally. */
    val startupError: String? = null,
    /** Unexpected coordinator failure (executor submit, preference read), null normally. */
    val coordinatorError: String? = null,
)

/** Store operations one [RuleUpdateCoordinator] needs; implemented per rule type. */
interface StoreOps<S, P, R> {
    fun checkForUpdates(store: S, manual: Boolean): R
    fun status(store: S): RuleUpdateStatus
    fun policy(store: S): P
    fun startupNotice(store: S): String?

    /** True when [result] is a throttled no-op (eligible for a manual rerun). */
    fun isThrottled(result: R): Boolean
}

/** Message nouns for one [RuleUpdateCoordinator] owner. */
data class CoordinatorLabels(
    /** `"NYT rule update"` / `"Site rule update"`. */
    val updateNoun: String,
    /** `"NYT reading fixes"` / `"Site reading fixes"`. */
    val unavailableNoun: String,
    /** `"rule"` / `"catalog"` (`"couldn't load the bundled …"`). */
    val bundledNoun: String,
)

/**
 * App-owned rule lifecycle: initializes the rule store once (bundled asset +
 * app-private cache) and runs automatic and manual update checks on an
 * injected background executor. Pure JVM core — Android wiring (assets,
 * storage dir, preferences) arrives as lambdas, so host tests drive fakes
 * without touching Android stubs. The NYT coordinator keeps its exact public
 * API as a thin subclass; production runs one shared-catalog coordinator.
 *
 * Threading: [ensureStarted], [onRead], [checkUpdates], [uiState] and
 * [isChecking] return immediately and are main-safe; init, disk and network
 * run only on [runBackground] (or inline in [policyForReading], which callers
 * use off the main thread). Published init state is an immutable volatile
 * snapshot, so UI reads never wait behind store initialization; [initLock]
 * only serializes factory runs. A failed executor submission, preference
 * read or other unexpected step resets the checking flag, delivers waiting
 * callbacks once with null, and exposes the real error in
 * [RuleUiState.coordinatorError]; it never leaves `checking` stuck on.
 * [policyForReading] runs init inline when the background task has not
 * finished, so callers must use it off the main thread (article reads
 * already run on `readly-read`); it never runs network, only the fast
 * asset/cache reads. Concurrent triggers coalesce to one update: waiters
 * share the in-flight result, except a throttled auto run reruns once when a
 * manual waiter is attached (manual bypasses the throttle).
 */
open class RuleUpdateCoordinator<S : Any, P : Any, R : Any>(
    private val storeFactory: () -> S,
    private val isAutoUpdateEnabled: () -> Boolean,
    private val runBackground: (Runnable) -> Unit,
    private val ops: StoreOps<S, P, R>,
    private val labels: CoordinatorLabels,
    private val onWarn: (String) -> Unit = { android.util.Log.w("Readly", it) },
) {
    /** Serializes factory runs only; UI reads never take this lock. */
    private val initLock = Any()

    /** Published init result; volatile so [uiState] never blocks on init. */
    private data class InitState<S>(
        val store: S?,
        val startupNotice: String?,
        val startupError: String?,
    )

    @Volatile
    private var initState: InitState<S>? = null

    /** Last unexpected coordinator failure; cleared by the next clean check. */
    @Volatile
    private var coordinatorError: String? = null

    private val checkGuard = Any()
    private var checkRunning = false

    private inner class Waiter(
        val manual: Boolean,
        val onDone: ((R?) -> Unit)?,
    )

    private val waiters = mutableListOf<Waiter>()

    /**
     * Posts initialization plus one automatic check to the background
     * executor. Idempotent and cheap to repeat: init runs once, later auto
     * checks throttle inside the store, and a disabled toggle skips them.
     */
    fun ensureStarted() {
        checkUpdates(manual = false, onDone = null)
    }

    /** Runs one automatic check on the background executor (call after a read). */
    fun onRead() {
        checkUpdates(manual = false, onDone = null)
    }

    /**
     * Runs one check on the background executor, coalesced with any
     * in-flight check. [onDone] fires on the background thread with the
     * shared result, or null when no store exists or the check could not
     * run (see [uiState] for the startup/coordinator error); callback
     * failures are logged, never thrown. A failed executor submission
     * resets `checking` at once instead of stranding it.
     */
    fun checkUpdates(manual: Boolean, onDone: ((R?) -> Unit)? = null) {
        synchronized(checkGuard) {
            if (checkRunning) {
                waiters.add(Waiter(manual, onDone))
                return
            }
            checkRunning = true
        }
        try {
            runBackground(Runnable { runCheck(manual, onDone) })
        } catch (e: Exception) {
            val error =
                "${labels.updateNoun} check couldn't start: ${e.javaClass.name}: ${e.message}"
            coordinatorError = error
            onWarn(error)
            // A second check may have coalesced while submission was blocked;
            // drain every waiter atomically so none waits for a later check.
            deliverCompletion(manual, onDone, null)
        }
    }

    /** True while a check is running; main-safe. */
    fun isChecking(): Boolean = synchronized(checkGuard) { checkRunning }

    /**
     * Immediate snapshot for settings; main-safe, never blocks on I/O or
     * on store initialization.
     */
    fun uiState(): RuleUiState {
        val init = initState
        val checking = synchronized(checkGuard) { checkRunning }
        val coordError = coordinatorError
        val current = init?.store
        if (current == null) {
            return RuleUiState(
                ready = false,
                checking = checking,
                startupNotice = init?.startupNotice,
                startupError = init?.startupError,
                coordinatorError = coordError,
            )
        }
        // Both accessors return the immutable snapshot without waiting.
        val status = ops.status(current)
        return RuleUiState(
            ready = true,
            checking = checking,
            version = status.version,
            origin = status.origin,
            lastCheckMs = status.lastCheckMs,
            lastError = status.lastError,
            warning = status.warning,
            startupNotice = init.startupNotice,
            startupError = init.startupError,
            coordinatorError = coordError,
        )
    }

    /**
     * The current rules for one reading, initializing inline when the
     * background task has not finished. Call off the main thread on first
     * use (init reads the asset and cache); never runs network. Null when
     * the bundled rules themselves are missing or invalid — callers then
     * read with the default opener and surface [uiState]'s startup error.
     */
    fun policyForReading(): P? = ensureStore()?.let { ops.policy(it) }

    /**
     * The current store without initializing: null until the background (or
     * an inline) init has finished. Main-safe; settings uses this to render
     * extra summary lines only when they are already available.
     */
    protected fun storeIfReady(): S? = initState?.store

    /**
     * Builds or returns the store, publishing [InitState]/startup error.
     * Serialized; a failed init retries on the next call so transient disk
     * failures can recover without restarting the app. UI reads observe the
     * volatile snapshot and never wait for the factory.
     */
    private fun ensureStore(): S? {
        initState?.store?.let { return it }
        synchronized(initLock) {
            initState?.store?.let { return it }
            try {
                val created = storeFactory()
                initState = InitState(created, ops.startupNotice(created), null)
            } catch (e: Exception) {
                val error =
                    "${labels.unavailableNoun} unavailable: couldn't load the bundled " +
                    "${labels.bundledNoun}: ${e.javaClass.name}: ${e.message}"
                initState = InitState(null, initState?.startupNotice, error)
                onWarn(error)
            }
            return initState?.store
        }
    }

    /**
     * One coalesced check on the background thread. [checkUpdates] already
     * marked [checkRunning]; waiters collected meanwhile share the result
     * below. Any unexpected failure resets the flag, records a
     * coordinator-visible error, and still delivers every waiter exactly
     * once.
     */
    private fun runCheck(manual: Boolean, onDone: ((R?) -> Unit)?) {
        var result: R? = null
        try {
            result = runCheckOnce(manual)
            val rerunManual = synchronized(checkGuard) {
                (result == null || (result != null && ops.isThrottled(result!!))) &&
                    !manual && waiters.any { it.manual }
            }
            if (rerunManual) {
                result = runCheckOnce(manual = true)
            }
            // A clean run clears a previous coordinator failure.
            coordinatorError = null
        } catch (e: Exception) {
            val error = "${labels.updateNoun} check failed: ${e.javaClass.name}: ${e.message}"
            coordinatorError = error
            onWarn(error)
            result = null
        } finally {
            deliverCompletion(manual, onDone, result)
        }
    }

    /**
     * Atomically resets the in-flight check and delivers [result] once to the
     * initiator plus every coalesced waiter, outside the lock. Shared by the
     * normal path and executor-submission failure so no waiter is stranded.
     */
    private fun deliverCompletion(
        manual: Boolean,
        onDone: ((R?) -> Unit)?,
        result: R?,
    ) {
        val deliver = synchronized(checkGuard) {
            val all = listOf(Waiter(manual, onDone)) + waiters.toList()
            waiters.clear()
            checkRunning = false
            all
        }
        deliver.forEach { waiter ->
            try {
                waiter.onDone?.invoke(result)
            } catch (e: Exception) {
                onWarn("${labels.updateNoun} callback failed: ${e.javaClass.name}: ${e.message}")
            }
        }
    }

    private fun runCheckOnce(manual: Boolean): R? {
        val current = ensureStore() ?: return null
        if (!manual && !isAutoUpdateEnabled()) return null
        return ops.checkForUpdates(current, manual)
    }
}
