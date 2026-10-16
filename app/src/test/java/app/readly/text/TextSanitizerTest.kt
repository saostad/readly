package app.readly.text

import org.junit.Assert.assertEquals
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
}
