package app.readly.article

import app.readly.ReadlyApp
import app.readly.input.ArticleFetcher
import app.readly.input.sites.SiteBrowserPolicy
import app.readly.input.sites.SiteUsage
import app.readly.text.SourceFormat
import app.readly.text.TextSanitizer

/**
 * Turns one captured, rendered page into speakable text. Shared by the phone's
 * [app.readly.ArticleActivity] and the screenless [HeadlessArticleReader] used in
 * Android Auto, so both read exactly the same article from the same snapshot.
 */
object RenderedArticle {

    sealed interface Outcome {
        data class Ready(val text: String, val title: String, val notice: String?) : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** Blocking; call off the main thread. Never throws. */
    fun parse(
        app: ReadlyApp?,
        html: String,
        snapshotUrl: String?,
        gateFlags: Int,
        truncated: Boolean,
        updateWarning: String?,
        readHiddenText: Boolean,
    ): Outcome {
        return try {
            val snapshot = app?.siteRules?.snapshotForReading()
            val usage = SiteUsage()
            val doc = try {
                ArticleFetcher(ArticleFetcher::httpUrlOpener, readHiddenText = readHiddenText)
                    .extractRenderedHtml(html, snapshotUrl ?: "", snapshot, usage)
            } catch (e: Exception) {
                // Recognizable walls land here with an explanation, never spoken.
                return Outcome.Failed(e.message ?: "${e.javaClass.name}: (no details)")
            }
            val clean = TextSanitizer.sanitize(doc.text, SourceFormat.HTML, readHiddenText)
            if (clean.isBlank()) {
                return Outcome.Failed("Read page failed: no readable article text was found on this page.")
            }
            SiteBrowserPolicy.handoffTooLargeError(clean.length)?.let { return Outcome.Failed(it) }
            val startupError = if (snapshot == null) app?.siteRules?.uiState()?.startupError else null
            val gateNote = if (gateFlags > 0) "The page shows possible subscription prompts." else null
            val truncNote = if (truncated) "The rendered page was truncated to 5 MB." else null
            val notice = listOfNotNull(
                SiteBrowserPolicy.readingNotice(
                    doc.notice, usage.cleanupApplied, usage.noticeHost(), startupError,
                ),
                gateNote, truncNote, updateWarning,
            ).joinToString(" ").takeIf { it.isNotBlank() }
            Outcome.Ready(clean, doc.title, notice)
        } catch (e: Exception) {
            Outcome.Failed("Read page failed: ${e.javaClass.name}: ${e.message}")
        }
    }
}
