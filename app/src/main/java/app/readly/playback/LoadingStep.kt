package app.readly.playback

/**
 * What the reader is waiting on before audio plays, for the loading indicators on
 * the Now reading screen and the home mini player. Pure: derived from a
 * [ReaderSnapshot], so every screen names the same step.
 */
data class LoadingStep(
    val kind: Kind,
    /** The site being opened, the voice being loaded, or the download's description. */
    val detail: String,
    /** 0..100 when the step can measure itself (a voice download), else null. */
    val percent: Int?,
) {
    enum class Kind {
        /** Fetching the article's page and pulling out its text. */
        OPENING,

        /** Downloading a voice the reading needs. */
        DOWNLOADING_VOICE,

        /** Loading the voice model into memory (can take minutes the first time). */
        LOADING_VOICE,

        /** Reading has started but the first sentence is still being synthesized. */
        PREPARING,
    }

    companion object {
        /** The step [snap] is waiting on, or null when nothing is loading. */
        fun of(snap: ReaderSnapshot): LoadingStep? = when (snap.status) {
            ReaderStatus.FETCHING ->
                if (snap.downloadPercent >= 0) {
                    LoadingStep(Kind.DOWNLOADING_VOICE, snap.currentText, snap.downloadPercent.coerceIn(0, 100))
                } else {
                    LoadingStep(Kind.OPENING, snap.host, null)
                }
            ReaderStatus.LOADING_VOICE -> LoadingStep(Kind.LOADING_VOICE, snap.loadingVoice, null)
            ReaderStatus.READING ->
                if (snap.playedChunks == 0 && snap.total > 0) LoadingStep(Kind.PREPARING, "", null) else null
            else -> null
        }

        /**
         * [next] with its step clock and step-only fields kept consistent: the clock
         * restarts at [nowMs] when the step changes, and fields of a finished step clear.
         */
        fun stamp(previous: ReaderSnapshot, next: ReaderSnapshot, nowMs: Long): ReaderSnapshot {
            var out = next
            if (out.status != ReaderStatus.FETCHING && out.downloadPercent != -1) out = out.copy(downloadPercent = -1)
            if (out.status != ReaderStatus.LOADING_VOICE && out.loadingVoice.isNotEmpty()) out = out.copy(loadingVoice = "")
            val before = of(previous)?.kind
            val after = of(out)?.kind
            return when {
                after == null -> if (out.stepSinceMs != 0L) out.copy(stepSinceMs = 0L) else out
                after != before || out.stepSinceMs == 0L -> out.copy(stepSinceMs = nowMs)
                else -> out
            }
        }

        /** "8 s", "2 min 5 s": how long the step has run. */
        fun elapsed(sinceMs: Long, nowMs: Long): String {
            val seconds = ((nowMs - sinceMs) / 1000).coerceAtLeast(0)
            return if (seconds < 60) "$seconds s" else "${seconds / 60} min ${seconds % 60} s"
        }
    }
}
