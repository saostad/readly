package app.readly.article

/**
 * Pure add-on update policy for the actual BPC extension: daily throttle,
 * manual bypass, and null-update interpretation. No Gecko, no Android, so
 * host JVM tests drive it directly; [ArticleEngine] applies the decisions.
 */
object BpcUpdatePolicy {
    /** Persisted add-on update state (attempt time survives failures). */
    data class State(
        /** Last attempt time in epoch ms, 0 when never checked. */
        val lastCheckMs: Long = 0,
        /** Installed add-on version observed by the engine, null if unknown. */
        val installedVersion: String? = null,
        /** Full error of the last failed check, null when the last check succeeded. */
        val lastError: String? = null,
    )

    /**
     * True when a check may run now. Manual checks always run (a manual check
     * from settings may start the engine); automatic checks require the
     * master toggle and a 24h gap since the last attempt — including failed
     * attempts, so repeated links/restarts never hammer the update server.
     */
    fun shouldCheck(
        nowMs: Long,
        state: State,
        manual: Boolean,
        autoEnabled: Boolean,
    ): Boolean {
        if (manual) return true
        if (!autoEnabled) return false
        if (state.lastCheckMs <= 0) return true
        return nowMs - state.lastCheckMs >= ArticleConstants.UPDATE_THROTTLE_MS
    }

    /** Outcome of one update attempt, after interpreting the engine result. */
    sealed interface Outcome {
        /** Keep reading with [versionToKeep]; [warning] explains a failed check. */
        data class Keep(val versionToKeep: String?, val warning: String?) : Outcome
        /** The engine now runs [newVersion]. */
        data class Updated(val newVersion: String) : Outcome
    }

    /**
     * Maps one raw `controller.update()` completion to an (updatedVersion,
     * error) pair for [BpcUpdateCoordinator.UpdateResult]. A null controller
     * result (or a null GeckoResult) means "no update" — success, keep
     * current. An update that reports any other add-on id is an error, never
     * a silent no-update: acting on it would adopt a stranger.
     */
    fun mapEngineUpdate(
        expectedId: String,
        updatedId: String?,
        updatedVersion: String?,
        engineError: String?,
    ): Pair<String?, String?> {
        if (engineError != null) return Pair(null, engineError)
        if (updatedId == null) return Pair(null, null)
        if (updatedId != expectedId) {
            return Pair(null, "update returned unexpected add-on id $updatedId")
        }
        return Pair(updatedVersion, null)
    }

    /**
     * Interprets one `controller.update()` completion.
     *
     * @param installedVersion the known-good version before the check.
     * @param updatedVersion the version returned by update(), or null when the
     *   controller reported no update (null result is success-keep-current,
     *   NOT a failure).
     * @param error the failure detail, or null on success.
     */
    fun interpret(
        installedVersion: String?,
        updatedVersion: String?,
        error: String?,
    ): Outcome {
        if (error != null) {
            val warning = "Add-on update check failed ($error); " +
                "keeping ${describe(installedVersion)}."
            return Outcome.Keep(installedVersion, warning)
        }
        if (updatedVersion == null || updatedVersion == installedVersion) {
            return Outcome.Keep(installedVersion, null)
        }
        return Outcome.Updated(updatedVersion)
    }

    private fun describe(version: String?): String =
        if (version.isNullOrBlank()) "the installed add-on" else "add-on v$version"
}
