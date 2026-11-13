package app.readly.input

import app.readly.text.SourceFormat

/** What a share resolves to: read this text now, or fetch this URL first. */
sealed interface ShareRequest {
    data class Read(val title: String, val text: String, val format: SourceFormat) : ShareRequest
    data class Url(val url: String, val titleHint: String?) : ShareRequest
}

/**
 * Decides what an incoming share means. Pure Kotlin over plain values, so it is
 * unit-testable on the host JVM; the activity extracts the values from the `Intent`
 * (and reads any shared file while its URI permission is valid) and passes them in.
 */
object ShareInput {

    const val MAX_STREAM_BYTES = 5L * 1024 * 1024

    /**
     * A share whose non-URL text is this long or shorter is a headline plus a link;
     * longer text with a link in it is prose that happens to mention a link.
     */
    const val MAX_HEADLINE_CHARS = 140

    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"

    /**
     * Resolves a share. [streamText] is the shared file's content (`null` when no file
     * was shared); [streamSizeBytes] is its true byte size when the activity measured
     * one (it caps the read, so the size can exceed the text it passes).
     *
     * @throws IllegalArgumentException with a user-readable message when the share is
     *   empty, too large, or of an unreadable kind.
     */
    fun resolve(
        action: String?,
        type: String?,
        text: String?,
        subject: String?,
        htmlText: String?,
        streamName: String?,
        streamMime: String?,
        streamText: String?,
        streamSizeBytes: Long? = null,
    ): ShareRequest {
        if (action == ACTION_PROCESS_TEXT) {
            if (text.isNullOrBlank()) {
                throw IllegalArgumentException("Nothing was shared: the app sent no text, link or file.")
            }
            return ShareRequest.Read(firstLineTitle(text), text, SourceFormat.PLAIN)
        }
        if (streamText != null) return resolveStream(type, streamName, streamMime, streamText, streamSizeBytes)
        if (!htmlText.isNullOrBlank()) {
            val title = subject?.trim().orEmpty().ifBlank { firstLineTitle(text.orEmpty()) }
            return ShareRequest.Read(title, htmlText, SourceFormat.HTML)
        }
        if (text.isNullOrBlank()) {
            throw IllegalArgumentException("Nothing was shared: the app sent no text, link or file.")
        }
        asUrlRequest(text, subject)?.let { return it }
        return ShareRequest.Read(firstLineTitle(text), text, SourceFormat.PLAIN)
    }

    private fun resolveStream(
        type: String?,
        name: String?,
        mime: String?,
        content: String,
        sizeBytes: Long?,
    ): ShareRequest.Read {
        val displayName = name ?: "shared file"
        val size = sizeBytes ?: content.toByteArray().size.toLong()
        if (size > MAX_STREAM_BYTES) {
            throw IllegalArgumentException("File is too large to read (over 5 MB): $displayName")
        }
        if (content.isBlank()) {
            throw IllegalArgumentException("Nothing was shared: the app sent no text, link or file.")
        }
        val lowerName = (name ?: "").lowercase()
        val format = when {
            lowerName.endsWith(".txt") -> SourceFormat.PLAIN
            lowerName.endsWith(".md") || lowerName.endsWith(".markdown") -> SourceFormat.MARKDOWN
            lowerName.endsWith(".html") || lowerName.endsWith(".htm") -> SourceFormat.HTML
            else -> when (val kind = (mime ?: type ?: "").substringBefore(";").trim().lowercase()) {
                "text/markdown", "text/x-markdown" -> SourceFormat.MARKDOWN
                "text/html", "application/xhtml+xml" -> SourceFormat.HTML
                else if kind.startsWith("text/") -> SourceFormat.PLAIN
                else -> throw IllegalArgumentException(
                    "Readly can read pictures, PDF, text, Markdown and HTML files, " +
                        "not ${mime ?: type ?: "unknown"} ($displayName).",
                )
            }
        }
        val title = displayName.substringBeforeLast(".").ifBlank { displayName }
        return ShareRequest.Read(title, content, format)
    }

    /**
     * Returns a [ShareRequest.Url] when [text] is just one URL, or one URL plus a short
     * headline; `null` when the text should be read as prose instead.
     */
    private fun asUrlRequest(text: String, subject: String?): ShareRequest.Url? {
        val matches = urlPattern.findAll(text).toList()
        if (matches.size != 1) return null
        val url = matches[0].value.trimEnd(*URL_TRAILING)
        val remainder = text.removeRange(matches[0].range)
            .replace(Regex("\\s+"), " ").trim().trim('(', ')', '[', ']', '"', '\'').trim()
        if (remainder.length > MAX_HEADLINE_CHARS) return null
        val hint = remainder.ifBlank { subject?.trim().orEmpty() }.ifBlank { null }
        return ShareRequest.Url(url, hint)
    }

    /** First non-blank line, trimmed to 60 chars, as the reading title. */
    private fun firstLineTitle(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(60).orEmpty()

    private val urlPattern = Regex("""https?://\S+""")
    private val URL_TRAILING = charArrayOf(')', '.', ',', ';', ':', '!', '?', '"', '\'')
}
