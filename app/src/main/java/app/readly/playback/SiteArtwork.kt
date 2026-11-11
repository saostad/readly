package app.readly.playback

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

/**
 * The square the car's playback screen shows next to the title: the website's name
 * on Readly's colors, so you can see which site is reading. Remembers the last image,
 * since every sentence re-sends the same metadata.
 */
class SiteArtwork {

    companion object {
        /**
         * Sharp on a car's art square, yet small enough (RGB_565, ~130 KB) to send with
         * every sentence's metadata update without nearing the 1 MB binder limit.
         */
        private const val SIZE_PX = 256
        private const val PADDING_PX = 24
        private const val MAX_TEXT_PX = 46f
        private const val MIN_TEXT_PX = 22f
        private const val TEXT_STEP_PX = 3f
        private const val BAND_PX = 10f
        private const val CAPTION_PX = 16f
        private const val MAX_LINES = 4

        // Readly's own palette (colors.xml): surface and accent.
        private val BACKGROUND = Color.parseColor("#1D212A")
        private val BAND = Color.parseColor("#B4A2F7")
        private val TEXT = Color.parseColor("#E9EAF0")
        private val CAPTION = Color.parseColor("#A3A8B6")
    }

    private var lastName: String? = null
    private var lastBitmap: Bitmap? = null

    /** The artwork for [name], or null when there's no site to show. */
    @Synchronized
    fun forName(name: String?): Bitmap? {
        val clean = name?.trim().orEmpty()
        if (clean.isEmpty()) return null
        if (clean == lastName) return lastBitmap
        val bitmap = render(clean)
        lastName = clean
        lastBitmap = bitmap
        return bitmap
    }

    private fun render(name: String): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)
        canvas.drawRect(0f, 0f, SIZE_PX.toFloat(), BAND_PX, Paint().apply { color = BAND })

        val width = SIZE_PX - 2 * PADDING_PX
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        // Largest size at which every word fits on its own line (never split mid-word,
        // "Technolo / gy") and the name fits in MAX_LINES; past the minimum it ends "…".
        var size = ArtworkFit.textSize(name, MAX_TEXT_PX, MIN_TEXT_PX, TEXT_STEP_PX, width.toFloat()) { text, px ->
            paint.textSize = px
            paint.measureText(text)
        }
        var layout: StaticLayout
        while (true) {
            paint.textSize = size
            layout = StaticLayout.Builder.obtain(name, 0, name.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setMaxLines(MAX_LINES)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            val fits = layout.lineCount < MAX_LINES || layout.getEllipsisCount(layout.lineCount - 1) == 0
            if ((fits && layout.height <= SIZE_PX - 3 * PADDING_PX) || size <= MIN_TEXT_PX) break
            size -= TEXT_STEP_PX
        }
        canvas.save()
        canvas.translate(PADDING_PX.toFloat(), (SIZE_PX - layout.height) / 2f - CAPTION_PX / 2)
        layout.draw(canvas)
        canvas.restore()

        val caption = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CAPTION
            textSize = CAPTION_PX
            textAlign = Paint.Align.CENTER
            typeface = Typeface.SANS_SERIF
        }
        canvas.drawText("Readly", SIZE_PX / 2f, SIZE_PX - PADDING_PX.toFloat(), caption)
        return bitmap
    }
}

/** Pure text sizing for [SiteArtwork], JVM-testable without Android graphics. */
object ArtworkFit {
    /**
     * The largest size from [max] down to [min] (in [step]s) at which the name's
     * longest word fits in [width], as measured by [measure] (text, size → px).
     */
    fun textSize(
        name: String,
        max: Float,
        min: Float,
        step: Float,
        width: Float,
        measure: (String, Float) -> Float,
    ): Float {
        val words = name.split(Regex("\\s+")).filter { it.isNotEmpty() }
        var size = max
        while (size > min && words.any { measure(it, size) > width }) size -= step
        return size.coerceAtLeast(min)
    }
}
