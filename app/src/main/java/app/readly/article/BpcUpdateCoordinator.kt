package app.readly.article

/**
 * App-owned add-on update lifecycle for the actual BPC extension: daily
 * throttle, coalesced checks, and retained-installed recovery. Pure JVM core
 * — persistence, clock, toggle and the engine update call arrive as lambdas,
 * so host tests drive fakes without Gecko.
 *
 * Threading: [checkUpdates] and [uiState] return immediately and are
 * main-safe; state I/O and [updateRunner] run only on [runBackground] (the
 * runner's callback may arrive on another thread and is serialized here).
 * Concurrent triggers coalesce to one update: waiters share the in-flight
 * result. Every attempt persists its timestamp BEFORE the network runs, so
 * a kill or restart mid-check still throttles the next automatic check.
 * Each attempt completes exactly once: a watchdog ([timeoutMs] via
 * [scheduleTimeout]) fails hung checks visibly, and late or double
 * callbacks from a retired attempt are ignored. Failures keep the
 * known-good installed add-on; they never uninstall it or reinstall the
 * bundled older copy.
 */
class BpcUpdateCoordinator(
    private val store: BpcUpdateStore,
    private val isAutoEnabled: () -> Boolean,
    private val clock: () -> Long,
    private val runBackground: (Runnable) -> Unit,
    /**
     * Runs one engine update (`controller.update` on the known BPC id) and
     * invokes [onDone] exactly once with the outcome. [installedVersion] is
     * the known-good version before the check; a null [UpdateResult.version]
     * with null [UpdateResult.error] means "no update" (success, keep
     * current). Never called for throttled or disabled automatic checks.
     */
    private val updateRunner: (installedVersion: String?, onDone: (UpdateResult) -> Unit) -> Unit,
    /** Hung checks fail after this long; late completions are then ignored. */
    private val timeoutMs: Long,
    /** Schedules the watchdog; test fakes control time, production posts. */
    private val scheduleTimeout: (delayMs: Long, task: Runnable) -> Unit,
    private val onWarn: (String) -> Unit = { android.util.Log.w("Readly", it) },
) {
    /** One engine update completion. */
    data class UpdateResult(
        /** Version after update, or null when the controller reported no update. */
        val version: String?,
        /** Failure detail, or null on success. */
        val error: String?,
    )

    /** One check outcome delivered to waiters. */
    data class CheckResult(
        /** True when this check ran the engine updater (not throttled/skipped). */
        val attempted: Boolean,
        val outcome: BpcUpdatePolicy.Outcome,
    )

    private val guard = Any()
    private var checkRunning = false
    private val waiters = mutableListOf<(CheckResult) -> Unit>()
    private var attemptCounter = 0
    private var openAttempt = -1
    /** Unsaved failure text (persistence itself failed); shown until saved. */
    private var transientError: String? = null

    /**
     * Runs one check, coalesced with any in-flight check. [onDone] fires with
     * the shared result (on the runner callback thread); callback failures
     * are logged, never thrown.
     */
    fun checkUpdates(manual: Boolean, onDone: ((CheckResult) -> Unit)? = null) {
        val immediate: CheckResult? = synchronized(guard) {
            if (checkRunning) {
                if (onDone != null) waiters.add(onDone)
                return
            }
            checkRunning = true
            if (onDone != null) waiters.add(onDone)
            null
        }
        if (immediate != null) {
            onDone?.invoke(immediate)
            return
        }
        try {
            runBackground(Runnable { runCheck(manual) })
        } catch (e: Exception) {
            val error = "Add-on update check couldn't start: ${e.javaClass.name}: ${e.message}"
            onWarn(error)
            deliver(CheckResult(false, BpcUpdatePolicy.Outcome.Keep(safeInstalled(), error)))
        }
    }

    /** True while a check is running; main-safe. */
    fun isChecking(): Boolean = synchronized(guard) { checkRunning }

    /** Immediate status for settings; main-safe, never blocks. */
    fun uiState(): BpcUiState {
        val state = try {
            store.load()
        } catch (e: Exception) {
            onWarn("Add-on status load failed: ${e.javaClass.name}: ${e.message}")
            BpcUpdatePolicy.State()
        }
        return BpcUiState(
            checking = synchronized(guard) { checkRunning },
            installedVersion = state.installedVersion,
            lastCheckMs = state.lastCheckMs,
            lastError = synchronized(guard) { transientError } ?: state.lastError,
        )
    }

    /** Records an engine-observed installed version without a check. */
    fun noteInstalled(version: String?) {
        if (version.isNullOrBlank()) return
        try {
            val current = store.load()
            if (current.installedVersion != version) {
                store.save(current.copy(installedVersion = version))
            }
        } catch (e: Exception) {
            onWarn("Add-on version note failed: ${e.javaClass.name}: ${e.message}")
        }
    }

    /**
     * Records a setup failure that prevented the updater from running (the
     * manual path surfaces it too). Persists only the error text: the
     * throttle timestamp is untouched, so a failed prepare never suppresses
     * the next automatic check.
     */
    fun notePrepareFailure(detail: String) {
        try {
            val current = store.load()
            store.save(current.copy(lastError = detail))
        } catch (e: Exception) {
            onWarn("Add-on prepare-failure note failed: ${e.javaClass.name}: ${e.message}")
            synchronized(guard) { transientError = detail }
        }
    }

    private fun runCheck(manual: Boolean) {
        val now = try {
            clock()
        } catch (e: Exception) {
            onWarn("Add-on clock failed: ${e.javaClass.name}: ${e.message}")
            deliver(
                CheckResult(
                    false,
                    BpcUpdatePolicy.Outcome.Keep(safeInstalled(), "clock failed"),
                ),
            )
            return
        }
        val state = try {
            store.load()
        } catch (e: Exception) {
            onWarn("Add-on state load failed: ${e.javaClass.name}: ${e.message}")
            BpcUpdatePolicy.State()
        }
        val auto = try {
            isAutoEnabled()
        } catch (e: Exception) {
            onWarn("Add-on toggle read failed: ${e.javaClass.name}: ${e.message}")
            false
        }
        if (!BpcUpdatePolicy.shouldCheck(now, state, manual, auto)) {
            deliver(
                CheckResult(
                    false,
                    BpcUpdatePolicy.Outcome.Keep(state.installedVersion, null),
                ),
            )
            return
        }
        val attempt = synchronized(guard) {
            attemptCounter += 1
            openAttempt = attemptCounter
            attemptCounter
        }
        // Persist the attempt BEFORE the network runs: a kill or restart
        // mid-check still throttles the next automatic check.
        var preWarning: String? = null
        try {
            store.save(state.copy(lastCheckMs = now))
        } catch (e: Exception) {
            preWarning =
                "Add-on attempt time couldn't be saved (${e.javaClass.name}: ${e.message})"
            onWarn("$preWarning; the check still runs.")
        }
        try {
            scheduleTimeout(timeoutMs, Runnable { onTimeout(attempt, now, state.installedVersion) })
        } catch (e: Exception) {
            onWarn("Add-on update watchdog couldn't start: ${e.javaClass.name}: ${e.message}")
        }
        try {
            updateRunner(state.installedVersion) { result ->
                finishAttempt(attempt, now, state.installedVersion, result, preWarning)
            }
        } catch (e: Exception) {
            finishAttempt(
                attempt,
                now,
                state.installedVersion,
                UpdateResult(null, "${e.javaClass.name}: ${e.message}"),
                preWarning,
            )
        }
    }

    private fun onTimeout(attempt: Int, nowMs: Long, installedVersion: String?) {
        finishAttempt(
            attempt,
            nowMs,
            installedVersion,
            UpdateResult(null, "timed out after ${timeoutMs}ms"),
            preWarning = null,
        )
    }

    /**
     * Completes [attempt] exactly once: the first completion (runner,
     * watchdog, or synchronous throw) wins; late or double callbacks from a
     * retired attempt are ignored and change nothing.
     */
    private fun finishAttempt(
        attempt: Int,
        nowMs: Long,
        installedVersion: String?,
        result: UpdateResult,
        preWarning: String?,
    ) {
        synchronized(guard) {
            if (!checkRunning || attempt != openAttempt) return
            openAttempt = -1
        }
        val outcome = BpcUpdatePolicy.interpret(installedVersion, result.version, result.error)
        val toPersist = when (outcome) {
            is BpcUpdatePolicy.Outcome.Updated -> outcome.newVersion
            is BpcUpdatePolicy.Outcome.Keep -> outcome.versionToKeep
        }
        // A persistence failure is itself a visible warning: the last error
        // records it (the check's own error wins when both exist), and the
        // working installed add-on is retained either way.
        val finalError = result.error ?: preWarning
        var delivered: BpcUpdatePolicy.Outcome = outcome
        try {
            store.save(
                BpcUpdatePolicy.State(
                    lastCheckMs = nowMs,
                    installedVersion = toPersist,
                    lastError = finalError,
                ),
            )
            synchronized(guard) { transientError = null }
        } catch (e: Exception) {
            val saveWarning = "Add-on update state couldn't be saved " +
                "(${e.javaClass.name}: ${e.message}); " +
                "keeping ${describeVersion(toPersist)}."
            onWarn(saveWarning)
            synchronized(guard) { transientError = saveWarning }
            if (delivered is BpcUpdatePolicy.Outcome.Keep) {
                val keep = delivered as BpcUpdatePolicy.Outcome.Keep
                delivered = keep.copy(
                    warning = listOfNotNull(keep.warning, saveWarning).joinToString(" "),
                )
            }
        }
        if (result.error != null) {
            onWarn("Add-on update check failed: ${result.error}")
        }
        deliver(CheckResult(true, delivered))
    }

    private fun describeVersion(version: String?): String =
        if (version.isNullOrBlank()) "the installed add-on" else "add-on v$version"

    private fun safeInstalled(): String? = try {
        store.load().installedVersion
    } catch (_: Exception) {
        null
    }

    private fun deliver(result: CheckResult) {
        val deliver = synchronized(guard) {
            val all = waiters.toList()
            waiters.clear()
            checkRunning = false
            all
        }
        deliver.forEach { waiter ->
            try {
                waiter(result)
            } catch (e: Exception) {
                onWarn("Add-on update callback failed: ${e.javaClass.name}: ${e.message}")
            }
        }
    }
}
