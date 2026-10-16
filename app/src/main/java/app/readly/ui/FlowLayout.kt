package app.readly.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/** Lays children left to right, wrapping to a new row when they don't fit. */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    private val spacing: Int = (8 * resources.displayMetrics.density + 0.5f).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingStart - paddingEnd
        var rowWidth = 0
        var rowHeight = 0
        var totalHeight = paddingTop + paddingBottom
        var startedRow = false
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val need = child.measuredWidth + if (startedRow) spacing else 0
            if (startedRow && rowWidth + need > maxWidth) {
                totalHeight += rowHeight + spacing
                rowWidth = 0
                rowHeight = 0
                startedRow = false
            }
            rowWidth += child.measuredWidth + if (startedRow) spacing else 0
            rowHeight = max(rowHeight, child.measuredHeight)
            startedRow = true
        }
        totalHeight += rowHeight
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(totalHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val maxRight = right - left - paddingEnd
        var x = paddingStart
        var y = paddingTop
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val width = child.measuredWidth
            val height = child.measuredHeight
            if (x != paddingStart && x + width > maxRight) {
                x = paddingStart
                y += rowHeight + spacing
                rowHeight = 0
            }
            child.layout(x, y, x + width, y + height)
            x += width + spacing
            rowHeight = max(rowHeight, height)
        }
    }
}
