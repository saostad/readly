package app.readly.ui

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import app.readly.R
import app.readly.playback.LoadingStep

/** Shared wording and binding for the loading indicators (Now reading, mini player). */
object LoadingViews {

    fun title(context: Context, step: LoadingStep): String = context.getString(
        when (step.kind) {
            LoadingStep.Kind.OPENING -> R.string.step_opening
            LoadingStep.Kind.DOWNLOADING_VOICE -> R.string.step_downloading
            LoadingStep.Kind.LOADING_VOICE -> R.string.step_loading_voice
            LoadingStep.Kind.PREPARING -> R.string.step_preparing
        },
    )

    /** "nytimes.com · 12 s" or "Phonon Everly · 1 min 3 s". */
    fun detail(step: LoadingStep, sinceMs: Long): String = listOfNotNull(
        step.detail.ifBlank { null },
        if (sinceMs > 0) LoadingStep.elapsed(sinceMs, SystemClock.elapsedRealtime()) else null,
    ).joinToString(" · ")

    fun hint(context: Context, step: LoadingStep): String? = when (step.kind) {
        LoadingStep.Kind.OPENING -> context.getString(R.string.step_opening_hint)
        LoadingStep.Kind.LOADING_VOICE -> context.getString(R.string.step_loading_voice_hint)
        LoadingStep.Kind.PREPARING -> context.getString(R.string.step_preparing_hint)
        LoadingStep.Kind.DOWNLOADING_VOICE -> null
    }

    /** A determinate bar for a measured step (download %), a moving one otherwise. */
    fun bindBar(bar: ProgressBar, step: LoadingStep?) {
        bar.visibility = if (step != null) View.VISIBLE else View.GONE
        if (step == null) return
        val percent = step.percent
        bar.isIndeterminate = percent == null
        if (percent != null) bar.progress = percent
    }

    fun bindText(view: TextView, text: String?) {
        view.text = text.orEmpty()
        view.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }
}
