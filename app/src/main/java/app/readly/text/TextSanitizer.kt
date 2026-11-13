package app.readly.text

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Input format for [TextSanitizer]. PLAIN gets the same Markdown rules as MARKDOWN. */
enum class SourceFormat {
    PLAIN,
    MARKDOWN,
    HTML,
}

/**
 * Strips everything that must never be spoken (markup, links, code, tags, entities, emoji)
 * so only readable prose reaches the voice.
 *
 * Pure Kotlin (jsoup for HTML). Output is paragraphs separated by `"\n\n"`, single spaces
 * inside paragraphs, no leading or trailing whitespace.
 */
object TextSanitizer {

    /**
     * [readHiddenText] off (the default) keeps only what a reader sees on the page:
     * image descriptions (alt text) and screen-reader-only text are dropped. On, images
     * with a description are read as that description and screen-reader text stays.
     */
    fun sanitize(input: String, format: SourceFormat, readHiddenText: Boolean = false): String {
        // The only English wording this cleaner inserts is the code-block
        // announcement; in Persian text it is spoken in Persian. The
        // document language is decided from the text itself the same way
        // the reference `chunk_language` does (Arabic-script letters
        // outnumber the other letters); HTML tags are stripped first so
        // markup never votes. Callers detect the language afterwards, so
        // there is no language to pass in.
        val persian = isPersianText(input)
        val paras = when (format) {
            SourceFormat.HTML -> htmlParagraphs(input, readHiddenText, persian)
            SourceFormat.PLAIN, SourceFormat.MARKDOWN ->
                markdownParagraphs(if (readHiddenText) input else dropMarkdownImages(input), persian)
        }
        return paras.mapNotNull { cleanup(it) }.joinToString("\n\n")
    }

    private val htmlTag = Regex("""<[^>]*>""")

    /** True when Arabic-script letters outnumber the other letters. */
    internal fun isPersianText(text: String): Boolean {
        val plain = htmlTag.replace(text, " ")
        var arabic = 0
        var other = 0
        for (c in plain) {
            if (LatinFa.isArabicScript(c)) arabic++ else if (c.isLetter()) other++
        }
        return arabic > other
    }

    private fun codeBlockText(persian: Boolean): String =
        if (persian) "بلوک کد خوانده نشد." else "Code block skipped."

    /**
     * Minimal cleanup for the system TTS engine: Readly speaks the app's text as
     * given (punctuation and line breaks stay, as pauses), removing only URLs and
     * emoji, which can never be spoken.
     */
    fun forSystemTts(input: String): String = stripEmoji(removeUrls(input)).trim()

    private data class Para(val text: String, val punctuate: Boolean)

    // ------------------------------------------------------------------
    // Shared cleanup (all formats)
    // ------------------------------------------------------------------

    // X photo links are bare `pic.x.com/…` tokens (no scheme), so the URL rule
    // must name them explicitly or they get read aloud.
    private val bareUrl = Regex("""https?://\S+|www\.\S+|(?<!\w)pic\.(?:x|twitter)\.com/\S+""")
    private val urlTrailingPunct = setOf(')', '.', ',', ';', ':', '!', '?', '"', '\'')
    private val bracketRef =
        Regex("""\[(\d{1,3}|[a-z]|citation needed|note \d+|nb \d+)\]""", RegexOption.IGNORE_CASE)
    private val commaRun = Regex("""(\s*,){2,}""")
    private val commaBeforePunct = Regex(""",\s*([.!?])""")
    private val trailingComma = Regex(""",\s*$""")
    private val spaceBeforePunct = Regex("""\s+([,.;:!?])""")
    private val entity = Regex("""&(amp|lt|gt|quot|apos|nbsp);|&#(\d+);|&#x([0-9a-fA-F]+);""")
    private val dotsRun = Regex("""\.{2,}""")
    private val bangRun = Regex("""!{2,}""")
    private val questionRun = Regex("""\?{2,}""")
    private val runOfSame = Regex("""(.)\1\1+""")
    private val symbolToken = Regex("""^[#*_`|~^=+<>]+$""")
    private val keptSingleSymbol = setOf('*', '=', '+', '<', '>')
    private const val PUNCT_END = ".!?:;"

    private fun cleanup(para: Para): String? {
        var text = removeUrls(para.text)
        text = bracketRef.replace(text, "")
        text = decodeEntities(text)
        text = stripEmoji(text)
        // Ellipses and excited punctuation are meaningful: keep them in spoken form.
        text = dotsRun.replace(text, "…")
        text = bangRun.replace(text, "!")
        text = questionRun.replace(text, "?")
        text = runOfSame.replace(text) { m ->
            if (m.value[0].isLetterOrDigit()) m.value else ""
        }
        // Unmatched backticks are markup residue, never prose.
        text = text.replace("`", "")
        text = stripSymbolTokens(text).trim()
        // Removals above can strand a space before punctuation ("nice .").
        text = spaceBeforePunct.replace(text, "$1")
        // Empty table cells (icons/emoji removed above) strand commas: collapse
        // runs and drop a comma before sentence punctuation or end of text.
        text = commaRun.replace(text, ",")
        text = commaBeforePunct.replace(text, "$1")
        text = trailingComma.replace(text, "")
        text = spaceBeforePunct.replace(text, "$1")
        if (text.isEmpty()) return null
        if (para.punctuate && text.last() !in PUNCT_END) text += "."
        if (!text.any { it.isLetterOrDigit() }) return null
        return text
    }

    private fun removeUrls(text: String): String {
        var result = bareUrl.replace(text) { m ->
            var cut = m.value.length
            while (cut > 0 && m.value[cut - 1] in urlTrailingPunct) cut--
            m.value.substring(cut)
        }
        result = spaceBeforePunct.replace(result, "$1")
        return result
    }

    private fun decodeEntities(text: String): String = entity.replace(text) { m ->
        val named = m.groups[1]?.value
        if (named != null) {
            when (named) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> " " // nbsp
            }
        } else {
            val code = m.groups[2]?.value?.toIntOrNull()
                ?: m.groups[3]?.value?.toIntOrNull(16)
            if (code != null && code in 1..0x10FFFF && (code < 0xD800 || code > 0xDFFF)) {
                String(Character.toChars(code))
            } else {
                ""
            }
        }
    }

    /** Meaningful symbols that survive emoji stripping (units, legal marks, arrows). */
    private val keptSymbols = setOf(
        0x00B0, // ° degree
        0x2103, // ℃
        0x2109, // ℉
        0x00A9, // ©
        0x00AE, // ®
        0x2122, // ™
        0x2116, // №
        0x00A7, // §
        0x00B6, // ¶
        0x00B5, // µ
        0x2190, 0x2191, 0x2192, 0x2193, // ← → ↑ ↓
    )

    private fun stripEmoji(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val type = Character.getType(cp)
            val drop = cp !in keptSymbols && (
                type == Character.OTHER_SYMBOL.toInt() ||
                    type == Character.SURROGATE.toInt() ||
                    cp == 0xFE0E || cp == 0xFE0F || cp == 0x200D || cp == 0x20E3 ||
                    cp in 0x1F3FB..0x1F3FF || // skin-tone modifiers
                    cp in 0x1F1E6..0x1F1FF || // regional indicators
                    cp in 0xE0020..0xE007F // tag characters
                )
            if (!drop) out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    private fun stripSymbolTokens(text: String): String = text.split(Regex("""\s+"""))
        .filter { token ->
            token.isEmpty() || !symbolToken.matches(token) ||
                (token.length == 1 && token[0] in keptSingleSymbol)
        }
        .joinToString(" ")

    // ------------------------------------------------------------------
    // Markdown / plain text
    // ------------------------------------------------------------------

    private val htmlComment = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val atx = Regex("""^(#{1,6})\s+(.*)$""")
    private val atxTrailing = Regex("""\s+#+\s*$""")
    private val refDef = Regex("""^\[[^\]]+\]:""")
    private val footnoteDef = Regex("""^\[\^[^\]]*\]:""")
    private val quoteMarker = Regex("""^\s*(?:>\s?)+""")
    private val listMarker = Regex("""^\s*(?:[-*+]\s+|\d{1,9}[.)]\s+)""")
    private val taskBox = Regex("""^\[([ xX])\]\s*""")
    private val codeSpan = Regex("""`([^`]+)`""")
    private val codePlaceholder = Regex("\u0000(\\d+)\u0000")
    private val tag = Regex("""</?[A-Za-z][^>]*>""")
    private val badgeLink = Regex("""\[(!\[[^\]]*\](?:\([^)]*\)|\[[^\]]*\]))\](?:\([^)]*\)|\[[^\]]*\])""")
    private val image = Regex("""!\[([^\]]*)\]\([^)]*\)""")
    private val link = Regex("""\[([^\]]*)\]\([^)]*\)""")
    private val refLink = Regex("""\[([^\]]*)\]\[[^\]]*\]""")
    private val autolink = Regex("""<(?:https?://|www\.)[^<>\s]+>""")
    private val footnoteRef = Regex("""\[\^[^\]]*\]""")
    private val boldStar = Regex("""\*\*(.+?)\*\*""")
    private val boldUnder = Regex("""(?<!\w)__(.+?)__(?!\w)""")
    private val strike = Regex("""~~(.+?)~~""")
    private val italStar = Regex("""(?<!\w)\*([^*\n]+?)\*(?!\w)""")
    private val italUnder = Regex("""(?<!\w)_([^_\n]+?)_(?!\w)""")

    private fun markdownParagraphs(input: String, persian: Boolean): List<Para> {
        var text = input.replace("\r\n", "\n").replace('\r', '\n')
        text = text.removePrefix("\uFEFF")
        text = stripFrontMatter(text)
        text = htmlComment.replace(text, "")
        val paras = mutableListOf<Para>()
        val current = mutableListOf<String>()
        fun flush() {
            if (current.isNotEmpty()) {
                paras.add(Para(inline(current.joinToString("\n")), punctuate = false))
                current.clear()
            }
        }
        val lines = text.split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val stripped = line.trim()
            if (stripped.isEmpty()) {
                flush(); i++; continue
            }
            val fence = fenceMarker(stripped)
            if (fence != null) {
                flush()
                i = skipFence(lines, i, fence)
                paras.add(Para(codeBlockText(persian), punctuate = false))
                continue
            }
            if (stripped.isNotEmpty() && stripped.all { it == '=' }) {
                if (current.isNotEmpty()) {
                    paras.add(Para(inline(current.joinToString("\n")), punctuate = true))
                    current.clear()
                }
                i++; continue
            }
            if (stripped.isNotEmpty() && stripped.all { it == '-' }) {
                // Setext level 2 when text precedes it, else a horizontal rule.
                if (current.isNotEmpty()) {
                    paras.add(Para(inline(current.joinToString("\n")), punctuate = true))
                    current.clear()
                }
                i++; continue
            }
            if (isHorizontalRule(stripped)) {
                flush(); i++; continue
            }
            val heading = atxHeading(stripped)
            if (heading != null) {
                flush()
                if (heading.isNotEmpty()) paras.add(Para(inline(heading), punctuate = true))
                i++; continue
            }
            if (refDef.containsMatchIn(stripped) || footnoteDef.containsMatchIn(stripped)) {
                i++; continue
            }
            var content = line
            if (quoteMarker.containsMatchIn(line)) {
                content = quoteMarker.replace(line, "")
                if (content.trim().isEmpty()) {
                    flush(); i++; continue
                }
                val quoteHeading = atxHeading(content.trim())
                if (quoteHeading != null) {
                    flush()
                    if (quoteHeading.isNotEmpty()) {
                        paras.add(Para(inline(quoteHeading), punctuate = true))
                    }
                    i++; continue
                }
            }
            val item = listItem(content)
            if (item != null) {
                flush()
                if (item.trim().isNotEmpty()) {
                    paras.add(Para(inline(item.trim()), punctuate = true))
                }
                i++; continue
            }
            val trimmed = content.trim()
            if (isTableSeparator(trimmed)) {
                flush(); i++; continue
            }
            if ('|' in trimmed) {
                flush()
                paras.add(Para(tableRow(trimmed), punctuate = true))
                i++; continue
            }
            if (isTitleLineBreak(current.lastOrNull(), trimmed)) {
                paras.add(Para(inline(current.joinToString("\n")), punctuate = true))
                current.clear()
            }
            current.add(trimmed)
            i++
        }
        flush()
        return paras
    }

    /**
     * A single newline ends the sentence (short title line over a new sentence) when the
     * earlier line has no ending punctuation, is at most 60 chars, and the next line starts
     * with an uppercase letter or digit. Longer hard-wrapped prose still joins.
     */
    private fun isTitleLineBreak(prev: String?, next: String): Boolean {
        if (prev.isNullOrEmpty() || next.isEmpty()) return false
        return prev.last() !in ".!?:;," && prev.length <= 60 &&
            (next[0].isUpperCase() || next[0].isDigit())
    }

    private fun stripFrontMatter(text: String): String {
        val lines = text.split("\n")
        if (lines.isEmpty() || lines[0].trim() != "---") return text
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") return lines.drop(i + 1).joinToString("\n")
        }
        return text
    }

    private fun fenceMarker(stripped: String): Char? = when {
        stripped.startsWith("```") -> '`'
        stripped.startsWith("~~~") -> '~'
        else -> null
    }

    private fun skipFence(lines: List<String>, from: Int, fence: Char): Int {
        var i = from + 1
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.length >= 3 && trimmed.all { it == fence }) return i + 1
            i++
        }
        return i
    }

    private fun isHorizontalRule(stripped: String): Boolean {
        val markers = stripped.replace(" ", "").replace("\t", "")
        return markers.length >= 3 && markers.all { it == markers[0] } && markers[0] in "-*_"
    }

    private fun atxHeading(stripped: String): String? {
        val match = atx.matchEntire(stripped) ?: return null
        return atxTrailing.replace(match.groups[2]!!.value, "").trim()
    }

    private fun listItem(content: String): String? {
        if (!listMarker.containsMatchIn(content)) return null
        return taskBox.replace(listMarker.replace(content, ""), "")
    }

    private fun isTableSeparator(trimmed: String): Boolean {
        if ('-' !in trimmed) return false
        return trimmed.all { it == '|' || it == '-' || it == ':' || it == ' ' || it == '\t' }
    }

    /** Inline Markdown (links, emphasis, …) with code spans protected from every rule. */
    private fun inline(text: String): String {
        val codes = mutableListOf<String>()
        val masked = codeSpan.replace(text) { m ->
            codes.add(m.groups[1]!!.value)
            "\u0000${codes.size - 1}\u0000"
        }
        return restoreCodes(structureInline(masked), codes)
    }

    private fun tableRow(trimmed: String): String {
        val codes = mutableListOf<String>()
        val masked = codeSpan.replace(trimmed) { m ->
            codes.add(m.groups[1]!!.value)
            "\u0000${codes.size - 1}\u0000"
        }
        // Clean each cell first (icons/badges/emoji drop out), then join the
        // survivors, so emptied cells never strand runs of commas.
        return masked.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            .map { restoreCodes(structureInline(it), codes).trim() }
            .mapNotNull { cleanup(Para(it, punctuate = false)) }
            .joinToString(", ")
    }

    private fun structureInline(text: String): String {
        // Tags become a space (collapsed later) so inline tags never glue words.
        var result = tag.replace(text, " ")
        result = badgeLink.replace(result, "")
        result = image.replace(result, "$1")
        result = link.replace(result, "$1")
        result = refLink.replace(result, "$1")
        result = autolink.replace(result, "")
        result = footnoteRef.replace(result, "")
        result = boldStar.replace(result, "$1")
        result = boldUnder.replace(result, "$1")
        result = strike.replace(result, "$1")
        result = italStar.replace(result, "$1")
        result = italUnder.replace(result, "$1")
        return result
    }

    private fun restoreCodes(text: String, codes: List<String>): String =
        codePlaceholder.replace(text) { m ->
            codes.getOrNull(m.groups[1]!!.value.toIntOrNull() ?: -1) ?: m.value
        }

    // ------------------------------------------------------------------
    // HTML
    // ------------------------------------------------------------------

    private const val HTML_REMOVE =
        "script, style, noscript, template, svg, canvas, iframe, " +
            "nav, header, footer, aside, form, button"
    /**
     * Reference-style clutter that is never prose. Shared with `ArticleFetcher`,
     * which strips it before Readability runs (Readability4J drops `class`
     * attributes from its output, so these selectors can't match afterwards).
     */
    internal const val NON_PROSE_SELECTORS =
        "sup.reference, .mw-ref, ol.references, .references, .reflist, " +
            ".mw-references-wrap, .mw-editsection, .navbox, .hatnote, .noprint"
    /**
     * Text that is in the page for screen readers only and never shown (the common
     * "visually hidden" classes). Shared with `ArticleFetcher`, which strips it before
     * Readability drops the classes. Only removed when hidden text is off.
     */
    internal const val SCREEN_READER_ONLY_SELECTORS =
        "[class*=sr-only], [class*=visually-hidden], [class*=visuallyhidden], " +
            "[class*=screen-reader], [class*=screenreader], .element-invisible, .assistive-text"

    /** Markdown images (`![description](src)`), dropped whole when hidden text is off. */
    private fun dropMarkdownImages(text: String): String = image.replace(text, "")

    private val htmlBlocks = setOf("p", "div", "blockquote", "section", "article")
    private val htmlHeadings = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    private fun htmlParagraphs(input: String, readHiddenText: Boolean, persian: Boolean): List<Para> {
        val doc = Jsoup.parse(input)
        doc.select(HTML_REMOVE).remove()
        doc.select(NON_PROSE_SELECTORS).remove()
        if (!readHiddenText) doc.select(SCREEN_READER_ONLY_SELECTORS).remove()
        doc.select("sup").forEach { sup ->
            if (bracketRef.containsMatchIn(sup.text())) sup.remove()
        }
        // Badge links (an <a> whose only meaningful child is an <img>) are
        // removed before images become alt text, so badges are never spoken.
        doc.select("a").forEach { link ->
            val kids = link.children()
            if (kids.isNotEmpty() &&
                kids.all { it.tagName().equals("img", ignoreCase = true) } &&
                link.ownText().isBlank()
            ) {
                link.remove()
            }
        }
        doc.select("figure > img").remove()
        doc.select("img").forEach { img ->
            val alt = img.attr("alt")
            if (readHiddenText && alt.isNotBlank()) img.replaceWith(TextNode(" $alt ")) else img.remove()
        }
        val paras = mutableListOf<Para>()
        val inlineText = StringBuilder()
        fun flush() {
            if (inlineText.isNotBlank()) {
                paras.add(Para(inlineText.toString(), punctuate = false))
            }
            inlineText.clear()
        }
        collectHtml(doc.body() ?: return paras, paras, inlineText, ::flush, persian)
        flush()
        return paras
    }

    private fun collectHtml(
        node: Node,
        paras: MutableList<Para>,
        inlineText: StringBuilder,
        flush: () -> Unit,
        persian: Boolean,
    ) {
        for (child in node.childNodes()) {
            when (child) {
                is TextNode -> inlineText.append(child.wholeText)
                is Element -> when (child.tagName().lowercase()) {
                    "br", "hr" -> flush()
                    "pre" -> {
                        flush()
                        paras.add(prePara(child, persian))
                    }
                    "tr" -> {
                        flush()
                        paras.add(tableRowPara(child))
                    }
                    "li" -> {
                        flush()
                        paras.addAll(listParas(child))
                    }
                    in htmlHeadings -> {
                        flush()
                        val text = child.text().trim()
                        if (text.isNotEmpty()) paras.add(Para(text, punctuate = true))
                    }
                    "code" -> {
                        val raw = child.wholeText().trim()
                        if ('\n' in raw || '\r' in raw) {
                            flush()
                            paras.add(Para(codeBlockText(persian), punctuate = false))
                        } else {
                            inlineText.append(child.text())
                        }
                    }
                    in htmlBlocks -> {
                        flush()
                        collectHtml(child, paras, inlineText, flush, persian)
                        flush()
                    }
                    else -> collectHtml(child, paras, inlineText, flush, persian)
                }
                else -> Unit
            }
        }
    }

    private fun prePara(pre: Element, persian: Boolean): Para {
        val raw = pre.wholeText().trim()
        return if ('\n' in raw || '\r' in raw) {
            Para(codeBlockText(persian), punctuate = false)
        } else {
            Para(raw, punctuate = false)
        }
    }

    private fun tableRowPara(tr: Element): Para {
        val cells = tr.children()
            .filter { it.tagName().lowercase() == "td" || it.tagName().lowercase() == "th" }
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { cleanup(Para(it, punctuate = false)) }
        return Para(cells.joinToString(", "), punctuate = true)
    }

    private fun listParas(li: Element): List<Para> {
        val paras = mutableListOf<Para>()
        ownListText(li)?.let { paras.add(it) }
        // select() includes the element itself, so skip it to avoid doubling.
        for (nested in li.select("li")) {
            if (nested === li) continue
            ownListText(nested)?.let { paras.add(it) }
        }
        return paras
    }

    private fun ownListText(li: Element): Para? {
        val clone = li.clone()
        clone.select("ul, ol").remove()
        val own = clone.text().trim()
        return if (own.isEmpty()) null else Para(own, punctuate = true)
    }
}
