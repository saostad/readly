package app.readly.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Guards the round-21 narrow-strip fix without instrumentation: the `SheetDialog`
 * theme must keep every bottom sheet full-width (used together with
 * [Sheets.applyFullWidth] after `show()` in every sheet).
 */
class SheetsTest {

    private fun themesFile(): File {
        val candidates = listOf(
            File("src/main/res/values/themes.xml"),
            File("app/src/main/res/values/themes.xml"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("themes.xml not found (tried ${candidates.joinToString()})")
    }

    @Test
    fun sheetDialogTheme_keepsSheetsFullWidth() {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val tags = builder.parse(themesFile()).getElementsByTagName("style")
        var found = false
        for (i in 0 until tags.length) {
            val element = tags.item(i) as Element
            if (element.getAttribute("name") != "SheetDialog") continue
            found = true
            val items = mutableMapOf<String, String>()
            val kids = element.getElementsByTagName("item")
            for (j in 0 until kids.length) {
                val item = kids.item(j) as Element
                items[item.getAttribute("name")] = item.textContent.trim()
            }
            assertEquals("false", items["android:windowIsFloating"])
            assertEquals("@null", items["android:windowBackground"])
            assertEquals("100%", items["android:windowMinWidthMajor"])
            assertEquals("100%", items["android:windowMinWidthMinor"])
        }
        assertTrue("SheetDialog theme not found in themes.xml", found)
    }
}
