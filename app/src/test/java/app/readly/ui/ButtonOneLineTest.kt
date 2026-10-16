package app.readly.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

/**
 * The one-line rule: every button and chip style carries `maxLines=1` (with
 * `ellipsize=end` and uniform auto-sizing down to 11sp), and every `Button` or
 * chip-styled view in every layout uses one of those styles — so no button text
 * ever wraps to two lines. Code-built buttons (VoicesActivity) use the same
 * styles via `defStyleRes`, which this file-based test can't see.
 */
class ButtonOneLineTest {

    private data class Style(val name: String, val parent: String?, val items: Map<String, String>)

    private fun resDir(): File {
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError("res dir not found (tried ${candidates.joinToString()})")
    }

    private fun parseStyles(): Map<String, Style> {
        val values = File(resDir(), "values")
            .listFiles { file -> file.extension == "xml" } ?: emptyArray()
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val out = mutableMapOf<String, Style>()
        for (file in values) {
            val tags = builder.parse(file).getElementsByTagName("style")
            for (i in 0 until tags.length) {
                val element = tags.item(i) as Element
                val name = element.getAttribute("name")
                if (name.isBlank()) continue
                val items = mutableMapOf<String, String>()
                val kids = element.getElementsByTagName("item")
                for (j in 0 until kids.length) {
                    val item = kids.item(j) as Element
                    items[item.getAttribute("name")] = item.textContent.trim()
                }
                var parent = element.getAttribute("parent").ifBlank { null }
                    ?.removePrefix("@style/")
                if (parent == null && "." in name) parent = name.substringBeforeLast(".")
                out[name] = Style(name, parent, items)
            }
        }
        return out
    }

    /** Follows the style chain; null when the chain leaves app styles. */
    private fun lookup(styles: Map<String, Style>, name: String, attr: String): String? {
        var current: String? = name
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current)) {
            val style = styles[current] ?: return null
            style.items[attr]?.let { return it }
            current = style.parent
        }
        return null
    }

    @Test
    fun designSystemStyles_setOneLineRule() {
        val styles = parseStyles()
        val names = listOf("Btn.Primary", "Btn.Ghost", "Btn.Text", "Chip", "Chip.Selected", "Segment")
        for (name in names) {
            assertTrue("$name: unknown style", name in styles)
            assertTrue("$name: maxLines != 1", lookup(styles, name, "android:maxLines") == "1")
            assertTrue("$name: ellipsize != end", lookup(styles, name, "android:ellipsize") == "end")
            assertTrue(
                "$name: autoSizeTextType != uniform",
                lookup(styles, name, "android:autoSizeTextType") == "uniform",
            )
        }
    }

    @Test
    fun layoutButtons_useOneLineStyles() {
        val styles = parseStyles()
        val layouts = File(resDir(), "layout").listFiles { file -> file.extension == "xml" }
            ?: emptyArray()
        assertTrue("no layouts found", layouts.isNotEmpty())
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val bad = mutableListOf<String>()
        var checked = 0
        for (file in layouts) {
            val all = builder.parse(file).getElementsByTagName("*")
            for (i in 0 until all.length) {
                val element = all.item(i) as? Element ?: continue
                if (element.tagName != "Button" && element.tagName != "RadioButton") continue
                checked++
                val id = element.getAttribute("android:id").ifBlank { "(no id)" }
                val style = element.getAttribute("style").removePrefix("@style/").ifBlank { null }
                if (style == null) {
                    bad.add("${file.name} $id: no style")
                    continue
                }
                if (lookup(styles, style, "android:maxLines") != "1") {
                    bad.add("${file.name} $id: style $style lacks maxLines=1")
                }
            }
        }
        assertTrue("no buttons found in layouts", checked > 0)
        if (bad.isNotEmpty()) fail("Buttons without the one-line style:\n" + bad.joinToString("\n"))
    }
}
