package app.readly.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoadingStepTest {

    private val idle = ReaderSnapshot()

    @Test
    fun of_namesEachStepBeforeAudio() {
        val opening = idle.copy(status = ReaderStatus.FETCHING, host = "nytimes.com", currentText = "Opening nytimes.com…")
        assertEquals(LoadingStep(LoadingStep.Kind.OPENING, "nytimes.com", null), LoadingStep.of(opening))

        val downloading = opening.copy(currentText = "Downloading a Persian voice (226 MB)… 42%", downloadPercent = 42)
        assertEquals(LoadingStep.Kind.DOWNLOADING_VOICE, LoadingStep.of(downloading)?.kind)
        assertEquals(42, LoadingStep.of(downloading)?.percent)

        val loading = idle.copy(status = ReaderStatus.LOADING_VOICE, loadingVoice = "Phonon Everly")
        assertEquals(LoadingStep(LoadingStep.Kind.LOADING_VOICE, "Phonon Everly", null), LoadingStep.of(loading))

        // Reading started, first sentence not heard yet: still preparing.
        val preparing = idle.copy(status = ReaderStatus.READING, total = 30, playedChunks = 0)
        assertEquals(LoadingStep.Kind.PREPARING, LoadingStep.of(preparing)?.kind)
        assertNull(LoadingStep.of(preparing.copy(playedChunks = 1)))
        assertNull(LoadingStep.of(idle.copy(status = ReaderStatus.PAUSED, total = 30)))
        assertNull(LoadingStep.of(idle))
    }

    @Test
    fun stamp_restartsTheClockPerStepAndClearsFinishedFields() {
        val opening = LoadingStep.stamp(idle, idle.copy(status = ReaderStatus.FETCHING, host = "a.example"), nowMs = 1_000)
        assertEquals(1_000, opening.stepSinceMs)

        // Same step, later update (e.g. a new title): the clock keeps running.
        val sameStep = LoadingStep.stamp(opening, opening.copy(title = "Title"), nowMs = 4_000)
        assertEquals(1_000, sameStep.stepSinceMs)

        val download = LoadingStep.stamp(sameStep, sameStep.copy(downloadPercent = 0), nowMs = 5_000)
        assertEquals(5_000, download.stepSinceMs)

        val loading = LoadingStep.stamp(
            download,
            download.copy(status = ReaderStatus.LOADING_VOICE, loadingVoice = "Piper Amy"),
            nowMs = 9_000,
        )
        assertEquals(9_000, loading.stepSinceMs)
        assertEquals(-1, loading.downloadPercent)

        val playing = LoadingStep.stamp(
            loading,
            loading.copy(status = ReaderStatus.READING, total = 5, playedChunks = 1),
            nowMs = 20_000,
        )
        assertEquals(0, playing.stepSinceMs)
        assertEquals("", playing.loadingVoice)
    }

    @Test
    fun elapsed_readsAsSecondsThenMinutes() {
        assertEquals("0 s", LoadingStep.elapsed(5_000, 4_000))
        assertEquals("8 s", LoadingStep.elapsed(0, 8_900))
        assertEquals("2 min 5 s", LoadingStep.elapsed(0, 125_000))
    }
}
