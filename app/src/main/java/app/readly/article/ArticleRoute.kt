package app.readly.article

import app.readly.input.ShareInput
import app.readly.input.ShareRequest

/**
 * One input-box dispatch decision: open the automatic article engine for a
 * link, or read the text as prose. Uses [ShareInput.resolve] — the same
 * URL/headline classification as shares — so pasted links and shared links
 * behave identically.
 */
object ArticleRoute {
    sealed interface Decision {
        data class Article(val url: String) : Decision
        data class Prose(val title: String, val text: String) : Decision
    }

    /**
     * Routes pasted/typed [text]. A single URL (optionally with a short
     * headline, max 140 chars like shares) opens the article engine; anything
     * else — including long prose that merely mentions a link — is read as
     * prose. Blank input returns null (the caller shows its empty error).
     */
    fun routeInput(text: String): Decision? {
        if (text.isBlank()) return null
        return when (
            val request = ShareInput.resolve(
                action = ShareInput.ACTION_SEND,
                type = "text/plain",
                text = text,
                subject = null,
                htmlText = null,
                streamName = null,
                streamMime = null,
                streamText = null,
                streamSizeBytes = null,
            )
        ) {
            is ShareRequest.Url -> Decision.Article(request.url)
            is ShareRequest.Read -> Decision.Prose(request.title, request.text)
        }
    }
}
