package app.readly.article

/**
 * Pure BPC setup state shared by the normal read path and the manual
 * settings-check path: observed metadata, enabling, readiness, install
 * tracking, failure recording, and setup-attempt scoping. The engine calls
 * these same methods from both paths, so a cold existing-enabled add-on
 * prepared via settings converges on exactly the state a read expects —
 * never a half-adopted reference with unobserved flags.
 *
 * Every list/install/enable round belongs to one setup attempt. Callbacks
 * from a retired attempt (after [retry]) are ignored and report stale, so
 * an old failure can never poison a newer attempt. Live add-on manager
 * events (enabled/ready/disabled) are ground truth and always apply.
 * Readiness itself is never inferred from list/install results: only an
 * observed `onReady` sets it, for fresh and pre-installed copies alike —
 * and an update result preserves it only for the same observed revision.
 *
 * Read and manual callers share one prepare round via [joinPrepare]:
 * concurrent callers join the live round instead of superseding its
 * callbacks. Rounds settle via [settlePrepare], time out via
 * [timeoutPrepare], and retire via [retry]; every waiter fires exactly once.
 *
 * Pure JVM (no Android/Gecko): the engine owns one on main, and host tests
 * drive the same transitions.
 */
class BpcStartup {
    /** Point-in-time setup state for readiness and status decisions. */
    data class Snapshot(
        val known: Boolean,
        val enabledObserved: Boolean,
        val readyObserved: Boolean,
        val installPending: Boolean,
        val error: String?,
        val version: String?,
    )

    /** What a listed result requires next. */
    enum class ListedAction {
        /** No known copy; install the bundled add-on. */
        INSTALL_MISSING,

        /** A disabled known copy; enable it, then wait for ready. */
        ENABLE_FOUND,

        /** An enabled known copy; wait for observed ready. */
        WAIT_READY,

        /** The callback belongs to a retired attempt; ignore it. */
        STALE,
    }

    /** Result of joining the single shared prepare round. */
    sealed interface PrepareJoin {
        /** The caller started a new round and must run `list()`. */
        data class Started(val id: Int) : PrepareJoin

        /** A round is already live; the caller waits for its settle. */
        data class Joined(val id: Int) : PrepareJoin
    }

    private var attempt = 0
    private var known = false
    private var enabledObserved = false
    private var readyObserved = false
    private var installPending = false
    private var error: String? = null
    private var version: String? = null

    /** The installed revision that produced the observed readiness, if any. */
    private var readyVersion: String? = null

    /** The live prepare round's attempt id, null when no round is open. */
    private var prepareId: Int? = null

    /** Unsettled prepare waiters, tagged by the round they joined. */
    private val prepareWaiters = mutableListOf<Pair<Int, (String?) -> Unit>>()

    fun snapshot(): Snapshot = Snapshot(
        known, enabledObserved, readyObserved, installPending, error, version,
    )

    /**
     * True when a list/enable round is still needed: the add-on is unknown,
     * or a known copy is still disabled (e.g. after an enable failure and an
     * explicit retry). Known enabled copies are reused as-is.
     */
    fun needsPrepare(): Boolean = error == null && (!known || !enabledObserved)

    /** Starts one list/install/enable round; callbacks carry this id. */
    fun beginAttempt(): Int {
        attempt += 1
        return attempt
    }

    /**
     * Joins the single shared prepare round. The first caller starts a new
     * round ([PrepareJoin.Started]) and must run `list()`; concurrent
     * callers join the live round ([PrepareJoin.Joined]) instead of
     * superseding its callbacks. A non-null [onSettled] fires exactly once:
     * when its round settles, times out, or is retired by [retry].
     */
    fun joinPrepare(onSettled: ((String?) -> Unit)?): PrepareJoin {
        val live = prepareId
        if (live != null && live == attempt) {
            if (onSettled != null) prepareWaiters.add(Pair(live, onSettled))
            return PrepareJoin.Joined(live)
        }
        attempt += 1
        prepareId = attempt
        if (onSettled != null) prepareWaiters.add(Pair(attempt, onSettled))
        return PrepareJoin.Started(attempt)
    }

    /**
     * Settles one prepare round: releases that round's waiters with [error]
     * and closes the round when it is the live one. Late or duplicate
     * settles only drain orphaned waiters and never touch live state.
     * Returns true when [id] was the live round.
     */
    fun settlePrepare(id: Int, error: String?): Boolean {
        drainWaiters(id, error)
        if (id != attempt || prepareId != id) return false
        prepareId = null
        return true
    }

    /**
     * Times out one prepare round with [detail]: records the failure
     * (keep-first) and releases the round's waiters visibly, so a hung
     * list/install/enable can never strand a manual check. Stale timeouts
     * only drain orphaned waiters. Returns true when [id] was the live round.
     */
    fun timeoutPrepare(id: Int, detail: String): Boolean {
        val current = id == attempt && prepareId == id
        if (current) {
            prepareId = null
            if (error == null) error = detail
            drainWaiters(id, error)
        } else {
            drainWaiters(id, detail)
        }
        return current
    }

    private fun drainWaiters(id: Int, error: String?) {
        val mine = prepareWaiters.filter { it.first == id }
        if (mine.isEmpty()) return
        prepareWaiters.removeAll { it.first == id }
        mine.forEach {
            try {
                it.second(error)
            } catch (_: Exception) {
                // One bad waiter must not starve the rest; the engine wraps
                // its callbacks with logging already.
            }
        }
    }

    /** True when [id] is the live attempt. */
    fun isCurrent(id: Int): Boolean = id == attempt

    /**
     * Reconciles one `list()` result: adopts the known copy's metadata
     * identically for the read and manual paths. Returns [ListedAction.STALE]
     * (no state change) for a retired attempt.
     */
    fun onListed(
        id: Int,
        found: Boolean,
        enabled: Boolean,
        listedVersion: String?,
    ): ListedAction {
        if (id != attempt) return ListedAction.STALE
        if (!found) return ListedAction.INSTALL_MISSING
        known = true
        enabledObserved = enabled
        if (!listedVersion.isNullOrBlank()) version = listedVersion
        return if (enabled) ListedAction.WAIT_READY else ListedAction.ENABLE_FOUND
    }

    /**
     * Records one `list()` failure. Returns false (no state change) for a
     * retired attempt. Callers must surface the real error, never silently
     * fall through to an install.
     */
    fun onListFailed(id: Int, detail: String): Boolean {
        if (id != attempt) return false
        if (error == null) error = detail
        return true
    }

    /** Marks an install in flight. Call BEFORE calling install(). */
    fun noteInstallStarted() {
        installPending = true
    }

    /**
     * Adopts one `install()` result. Readiness still waits for observed
     * `onReady`. Returns false (no state change) for a retired attempt.
     */
    fun onInstalled(id: Int, enabled: Boolean, installedVersion: String?): Boolean {
        if (id != attempt) return false
        known = true
        enabledObserved = enabled
        if (!installedVersion.isNullOrBlank()) version = installedVersion
        return true
    }

    /** Records one `install()` failure; false for a retired attempt. */
    fun onInstallFailed(id: Int, detail: String): Boolean {
        if (id != attempt) return false
        installPending = false
        if (error == null) error = detail
        return true
    }

    /**
     * Reconciles one `enable()` result; false for a retired attempt. A
     * result that still reports disabled is a failure, never readiness.
     */
    fun onEnableResult(id: Int, enabled: Boolean, enabledVersion: String?): Boolean {
        if (id != attempt) return false
        if (!enabled) {
            if (error == null) error = "Article extension enable() returned a disabled state"
            return true
        }
        enabledObserved = true
        if (!enabledVersion.isNullOrBlank()) version = enabledVersion
        return true
    }

    /** Records one `enable()` call failure; false for a retired attempt. */
    fun onEnableFailed(id: Int, detail: String): Boolean {
        if (id != attempt) return false
        if (error == null) error = detail
        return true
    }

    /** Live `onEnabled`: the add-on reports itself enabled. Always applies. */
    fun onEnabledEvent(eventVersion: String?) {
        known = true
        enabledObserved = true
        if (!eventVersion.isNullOrBlank()) version = eventVersion
    }

    /** Live `onReady`: the add-on reports itself ready. Always applies. */
    fun onReadyEvent(eventVersion: String?) {
        readyObserved = true
        if (!eventVersion.isNullOrBlank()) {
            version = eventVersion
            readyVersion = eventVersion
        }
    }

    /** Live `onDisabled`: the add-on reports itself disabled. Always applies. */
    fun onDisabledEvent() {
        enabledObserved = false
        if (error == null) error = "Article extension reported disabled"
    }

    /**
     * Reconciles a successful `update()`: the new revision's version and
     * enabled flag apply. Readiness events can arrive before the update
     * promise resolves (with no later event guaranteed), so an already
     * observed `onReady` for the SAME new revision is preserved; readiness
     * from any other revision — or a disabled result — never blesses the new
     * one, and the replacement must report `onReady` again. Failures never
     * reach here: they leave the known-good state untouched.
     */
    fun onUpdateApplied(updatedVersion: String?, enabled: Boolean) {
        known = true
        enabledObserved = enabled
        val sameRevision = enabled && readyObserved &&
            !readyVersion.isNullOrBlank() &&
            !updatedVersion.isNullOrBlank() &&
            readyVersion == updatedVersion
        readyObserved = sameRevision
        if (!sameRevision) readyVersion = null
        if (!updatedVersion.isNullOrBlank()) version = updatedVersion
    }

    /**
     * Records an unscoped failure (startup watchdog, stray install-failure
     * event). Keep-first, like the engine's historic fail().
     */
    fun failNow(reason: String) {
        if (error == null) error = reason
    }

    /**
     * Explicit bounded retry after a failure: clears the failure and the
     * install flag, keeps the known add-on reference, its observed
     * metadata, and any observed readiness, and opens a fresh attempt whose
     * callbacks supersede all older ones. Retires any live prepare round and
     * releases its waiters visibly, so a hung round can't strand them.
     * Returns null when there is no failure to retry.
     */
    fun retry(): Int? {
        if (error == null) return null
        error = null
        installPending = false
        prepareId = null
        val stale = prepareWaiters.toList()
        prepareWaiters.clear()
        stale.forEach {
            try {
                it.second("Article extension setup was superseded by a newer attempt")
            } catch (_: Exception) {
            }
        }
        attempt += 1
        return attempt
    }
}
