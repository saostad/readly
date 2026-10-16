package app.readly.ui

import app.readly.model.TestVoices
import app.readly.model.VoiceCatalog
import app.readly.model.VoiceFamily
import app.readly.playback.ReaderService
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
 *
 * Round 21 adds the other half of the rule: labels must not be cut off with
 * `…` either. [voiceLabels_fitOneLine] sweeps every label-building function
 * over the whole catalog at ≤ 18 characters.
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

    /** Reads a `<string name="...">` value from `res/values/strings.xml`. */
    private fun stringValue(name: String): String {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val tags = builder.parse(File(resDir(), "values/strings.xml"))
            .getElementsByTagName("string")
        for (i in 0 until tags.length) {
            val element = tags.item(i) as? Element ?: continue
            if (element.getAttribute("name") == name) return element.textContent.trim()
        }
        throw AssertionError("string $name not found in strings.xml")
    }

    /**
     * No truncated labels: chips, segments and the main button fit one line
     * (≤ 18 chars) for every catalog pack and every Piper language. Only voice
     * names can push past it, and those are capped to their first word.
     */
    @Test
    fun voiceLabels_fitOneLine() {
        TestVoices.loadCatalog()
        val bad = mutableListOf<String>()
        fun check(label: String, what: String) {
            if (label.length > MAX_LABEL_CHARS) bad.add("$what: \"$label\" (${label.length})")
        }
        for (pack in VoiceCatalog.ALL) {
            if (pack.family == VoiceFamily.PIPER) {
                check(VoiceCatalog.baseQuality(pack.variant), "${pack.id} quality")
                check(
                    VoiceCatalog.sizeLabel(
                        VoiceCatalog.isCompactVariant(pack.variant),
                        pack.sizeMb,
                    ),
                    "${pack.id} size",
                )
            } else {
                check(
                    VoiceCatalog.segmentLabel(pack.variant, pack.sizeMb),
                    "${pack.id} segment",
                )
            }
            check(VoiceCatalog.downloadLabel(pack.sizeMb), "${pack.id} download button")
        }
        for (lang in VoiceCatalog.piperLangs()) {
            check(VoiceCatalog.langChipLabel(lang), "lang chip $lang")
        }
        val voices = VoiceCatalog.packsFor(VoiceFamily.PIPER)
            .mapNotNull { it.voiceName }.distinct()
        assertTrue("no Piper voices found", voices.isNotEmpty())
        for (voice in voices) {
            check(VoiceCatalog.voiceChipLabel(voice), "voice chip $voice")
        }
        // The disabled main-button states.
        check(stringValue("in_use_badge"), "in-use badge")
        val inUseFor = stringValue("in_use_for_format")
        for (bucket in listOf("fa", "ar")) {
            check(
                String.format(inUseFor, ReaderService.langName(bucket)),
                "in-use-for button $bucket",
            )
        }
        if (bad.isNotEmpty()) {
            fail("Labels over $MAX_LABEL_CHARS chars:\n" + bad.joinToString("\n"))
        }
    }

    companion object {
        private const val MAX_LABEL_CHARS = 18
    }
}
