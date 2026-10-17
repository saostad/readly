package app.readly.article

/**
 * Pure navigation gate for the article engine: navigation starts only after
 * observed extension readiness, never from install/enable alone.
 *
 * Both fresh installs and pre-installed same-version copies must report
 * enabled AND ready before navigation. Readiness/defaults are asynchronous,
 * and `onEnabled` may arrive before the install call returns, so callers set
 * [installPending] BEFORE calling install and keep waiting for [bpcReady]
 * either way. There is no path that navigates on enabled-alone.
 */
object ArticleReadiness {
    data class State(
        val captureHelperReady: Boolean,
        val bpcKnown: Boolean,
        val bpcEnabled: Boolean,
        val bpcReady: Boolean,
        /** True from just before install() until observed readiness. */
        val installPending: Boolean,
    )

    fun canNavigate(state: State): Boolean =
        state.captureHelperReady &&
            state.bpcKnown &&
            state.bpcEnabled &&
            state.bpcReady

    /** Human-readable wait reason for status UI, null when [canNavigate]. */
    fun waitReason(state: State): String? {
        if (canNavigate(state)) return null
        return when {
            !state.captureHelperReady -> "waiting for the capture helper"
            !state.bpcKnown -> if (state.installPending) {
                "installing the article extension"
            } else {
                "waiting for the article extension"
            }
            !state.bpcEnabled -> "waiting for the article extension to enable"
            !state.bpcReady -> if (state.installPending) {
                "waiting for the fresh install to become ready"
            } else {
                "waiting for the installed extension to become ready"
            }
            else -> "waiting for the article extension"
        }
    }
}
