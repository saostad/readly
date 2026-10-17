package app.readly.article

import android.content.SharedPreferences

/** Point-in-time add-on status for settings; main-safe, never blocks. */
data class BpcUiState(
    /** True while an update check is running. */
    val checking: Boolean,
    /** Installed add-on version observed by the engine, null if unknown. */
    val installedVersion: String?,
    /** Last attempt time in epoch ms, 0 when never checked. */
    val lastCheckMs: Long,
    /** Full error of the last failed check, null when the last check succeeded. */
    val lastError: String?,
)

/** Persisted add-on update state. */
interface BpcUpdateStore {
    fun load(): BpcUpdatePolicy.State
    fun save(state: BpcUpdatePolicy.State)
}

/** SharedPreferences-backed [BpcUpdateStore] (production). */
class PrefsBpcUpdateStore(
    private val prefs: SharedPreferences,
) : BpcUpdateStore {
    override fun load(): BpcUpdatePolicy.State = BpcUpdatePolicy.State(
        lastCheckMs = prefs.getLong(ArticleConstants.PREF_BPC_LAST_CHECK_MS, 0),
        installedVersion = prefs.getString(ArticleConstants.PREF_BPC_INSTALLED_VERSION, null),
        lastError = prefs.getString(ArticleConstants.PREF_BPC_LAST_ERROR, null),
    )

    override fun save(state: BpcUpdatePolicy.State) {
        prefs.edit()
            .putLong(ArticleConstants.PREF_BPC_LAST_CHECK_MS, state.lastCheckMs)
            .putString(ArticleConstants.PREF_BPC_INSTALLED_VERSION, state.installedVersion)
            .putString(ArticleConstants.PREF_BPC_LAST_ERROR, state.lastError)
            .apply()
    }
}

/** In-memory [BpcUpdateStore] for host JVM tests. */
class MemoryBpcUpdateStore(
    initial: BpcUpdatePolicy.State = BpcUpdatePolicy.State(),
) : BpcUpdateStore {
    @Volatile
    var state: BpcUpdatePolicy.State = initial
        private set

    var saves: Int = 0
        private set

    override fun load(): BpcUpdatePolicy.State = state

    override fun save(state: BpcUpdatePolicy.State) {
        this.state = state
        saves++
    }
}
