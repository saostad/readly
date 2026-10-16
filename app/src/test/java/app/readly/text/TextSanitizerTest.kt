package app.readly.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSanitizerTest {

    @Test
    fun sanitize_markdown_readmeFixture_producesSpokenProse() {
        val input = """
            ---
            title: Sample Doc
            author: Test
            ---

            # Hello World #

            This is *emphatic* and **strong** and __underlined__ and ~~gone~~ text with `inline code` and a [link](https://example.com/page) plus an image ![logo](pic.png), a [ref link][1] and <https://example.com/auto>.

            [1]: https://example.com/ref

            ## Features

            - First item
              - Nested one
              - Nested two
            - Second item with `code`
            - [ ] open task
            - [x] done task

            > A wise quote
            > across two lines.

            | Name | Age |
            | ---- | --- |
            | Alice | 30 |
            | Bob | 25 |

            ```kotlin
            fun main() {}
            ```

            ~~~
            tilde fenced
            ~~~

            Setext Title
            ===

            Emoji 🎉 test and bare https://example.com/abc, plus a footnote[^1].
            """.trimIndent()
        val expected = """
            Hello World.

            This is emphatic and strong and underlined and gone text with inline code and a link plus an image logo, a ref link and.

            Features.

            First item.

            Nested one.

            Nested two.

            Second item with code.

            open task.

            done task.

            A wise quote across two lines.

            Name, Age.

            Alice, 30.

            Bob, 25.

            Code block skipped.

            Code block skipped.

            Setext Title.

            Emoji test and bare, plus a footnote.
            """.trimIndent()
        assertEquals(expected, TextSanitizer.sanitize(input, SourceFormat.MARKDOWN))
    }

    @Test
    fun sanitize_plain_appliesSameMarkdownRules() {
        val input = "# Hello\n\nSome **bold** text and a [link](https://example.com/x)."
        val expected = "Hello.\n\nSome bold text and a link."
        assertEquals(expected, TextSanitizer.sanitize(input, SourceFormat.PLAIN))
        assertEquals(expected, TextSanitizer.sanitize(input, SourceFormat.MARKDOWN))
    }

    @Test
    fun sanitize_html_fixture_producesSpokenProse() {
        val input = """
            <html>
            <head><title>Ignored title</title><script>alert(1);</script><style>p { color: red; }</style></head>
            <body>
            <nav>Home | About</nav>
            <h1>Welcome</h1>
            <p>Hello &amp; welcome to <a href="https://example.com">Readly</a>.</p>
            <ul><li>First</li><li>Second</li></ul>
            <table><tr><th>Name</th><th>Age</th></tr><tr><td>Alice</td><td>30</td></tr></table>
            <pre>line1
            line2</pre>
            <p>Use <code>inline</code> code.</p>
            <footer>Footer text</footer>
            </body>
            </html>
            """.trimIndent()
        val expected = """
            Welcome.

            Hello & welcome to Readly.

            First.

            Second.

            Name, Age.

            Alice, 30.

            Code block skipped.

            Use inline code.
            """.trimIndent()
        assertEquals(expected, TextSanitizer.sanitize(input, SourceFormat.HTML))
    }

    @Test
    fun sanitize_html_nestedList_flattensWithPunctuation() {
        val input = "<ul><li>outer<ul><li>inner one</li><li>inner two</li></ul></li><li>tail</li></ul>"
        assertEquals(
            "outer.\n\ninner one.\n\ninner two.\n\ntail.",
            TextSanitizer.sanitize(input, SourceFormat.HTML),
        )
    }

    @Test
    fun sanitize_plain_prose_returnsIdentical() {
        val input = "Use snake_case_name in C# and rank #1. It costs 3 * 4 = 12 dollars."
        assertEquals(input, TextSanitizer.sanitize(input, SourceFormat.PLAIN))
    }

    @Test
    fun sanitize_emojiOnly_returnsEmpty() {
        assertEquals("", TextSanitizer.sanitize("🎉👍🏽🚀", SourceFormat.PLAIN))
        assertEquals("", TextSanitizer.sanitize("🎉👍🏽🚀", SourceFormat.MARKDOWN))
        assertEquals("", TextSanitizer.sanitize("<p>🎉👍🏽🚀</p>", SourceFormat.HTML))
    }

    @Test
    fun sanitize_symbolOnly_returnsEmpty() {
        assertEquals("", TextSanitizer.sanitize("*** ### ___", SourceFormat.PLAIN))
        assertEquals("", TextSanitizer.sanitize("*** ### ___", SourceFormat.MARKDOWN))
        assertEquals("", TextSanitizer.sanitize("<p>*** ### ___</p>", SourceFormat.HTML))
    }

    @Test
    fun sanitize_empty_returnsEmpty() {
        assertEquals("", TextSanitizer.sanitize("", SourceFormat.PLAIN))
        assertEquals("", TextSanitizer.sanitize("", SourceFormat.MARKDOWN))
        assertEquals("", TextSanitizer.sanitize("", SourceFormat.HTML))
    }

    @Test
    fun sanitize_punctuationRuns_keepSpokenForm() {
        assertEquals(
            "Wait… what?! This is cool!",
            TextSanitizer.sanitize("Wait... what?!! This is cool!!!", SourceFormat.PLAIN),
        )
        assertEquals(
            "Really? Yes…",
            TextSanitizer.sanitize("Really?? Yes..", SourceFormat.PLAIN),
        )
    }

    @Test
    fun sanitize_meaningfulSymbols_areKept() {
        val input = "It's 25°C today © 2026"
        assertEquals(input, TextSanitizer.sanitize(input, SourceFormat.PLAIN))
        val marks = "Nice™ product (®) → buy it ← § 5 ¶ 2 № 7 µ 100℉"
        assertEquals(marks, TextSanitizer.sanitize(marks, SourceFormat.PLAIN))
    }

    @Test
    fun sanitize_removalBeforePunctuation_leavesNoSpace() {
        assertEquals(
            "That was nice.",
            TextSanitizer.sanitize("That was nice 🎉.", SourceFormat.PLAIN),
        )
        // ™ is now a kept symbol, so this stays untouched.
        assertEquals("nice ™.", TextSanitizer.sanitize("nice ™.", SourceFormat.PLAIN))
    }

    @Test
    fun sanitize_inlineTags_doNotGlueWords() {
        assertEquals(
            "More Hidden text",
            TextSanitizer.sanitize(
                "<details><summary>More</summary>Hidden text</details>",
                SourceFormat.PLAIN,
            ),
        )
    }

    @Test
    fun sanitize_shortTitleLine_breaksBeforeNextSentence() {
        assertEquals(
            "Breaking News.\n\nThe market fell 3% today.",
            TextSanitizer.sanitize(
                "Breaking News\nThe market fell 3% today.",
                SourceFormat.PLAIN,
            ),
        )
    }

    @Test
    fun sanitize_hardWrappedProse_stillJoins() {
        val line1 = "The quick brown fox jumps over the lazy dog near the river bank today"
        val line2 = "And this next line starts uppercase but the first line is too long to split"
        assertTrue("fixture line must exceed the 60-char join guard", line1.length > 60)
        assertEquals(
            "$line1 $line2",
            TextSanitizer.sanitize("$line1\n$line2", SourceFormat.PLAIN),
        )
    }
}
