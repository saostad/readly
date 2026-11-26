package app.readly.ui

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import app.readly.R

/** Shared Calm Dark layout builders used by [app.readly.SettingsActivity] and sheets. */
object SettingsWidgets {

    fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun selectableBg(ctx: Context): Int {
        val out = TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        return out.resourceId
    }

    fun sectionLabel(ctx: Context, text: String): TextView =
        TextView(ctx, null, 0, R.style.SectionLabel).apply {
            this.text = text
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(ctx, 14)
                bottomMargin = dp(ctx, 6)
            }
        }

    fun cardGroup(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card_background)
        setPadding(dp(ctx, 14), dp(ctx, 4), dp(ctx, 14), dp(ctx, 4))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(ctx, 6) }
    }

    fun addDividedRow(card: LinearLayout, row: View) {
        if (card.childCount > 0) {
            card.addView(
                View(card.context).apply { setBackgroundColor(card.context.getColor(R.color.line)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1),
            )
        }
        card.addView(row)
    }

    data class HubRowHandles(
        val root: LinearLayout,
        val summaryView: TextView,
    )

    fun hubCategoryRow(
        ctx: Context,
        iconRes: Int,
        title: String,
        summary: String,
        onClick: () -> Unit,
    ): HubRowHandles {
        lateinit var subView: TextView
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, 64)
            setBackgroundResource(R.drawable.settings_row_card)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(ctx, 8) }
            addView(iconBadge(ctx, iconRes), badgeLayoutParams(ctx))
            val labels = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(
                TextView(ctx).apply {
                    text = title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                    setTextColor(ctx.getColor(R.color.text))
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            subView = TextView(ctx, null, 0, R.style.Small).apply {
                text = summary
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(ctx, 2), 0, 0)
            }
            labels.addView(subView)
            addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevronView(ctx))
            setOnClickListener { onClick() }
        }
        return HubRowHandles(row, subView)
    }

    fun switchRow(
        ctx: Context,
        title: String,
        subtitle: String,
        checked: Boolean,
        onToggle: (Boolean) -> Unit,
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(ctx, 56)
        setPadding(0, dp(ctx, 10), 0, dp(ctx, 10))
        val labels = titleSubtitleColumn(ctx, title, subtitle)
        val toggle = Switch(ctx).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onToggle(value) }
        }
        isClickable = true
        setOnClickListener { toggle.toggle() }
        addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(toggle)
    }

    data class NavRowHandles(
        val root: LinearLayout,
        val titleView: TextView,
        val subtitleView: TextView,
    )

    fun navRow(
        ctx: Context,
        iconRes: Int? = null,
        title: String,
        subtitle: String,
        onClick: () -> Unit,
    ): NavRowHandles {
        lateinit var titleView: TextView
        lateinit var subView: TextView
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, 56)
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 10))
            isClickable = true
            isFocusable = true
            background = ctx.getDrawable(selectableBg(ctx))
            if (iconRes != null) addView(iconBadge(ctx, iconRes), badgeLayoutParams(ctx))
            val labels = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            titleView = TextView(ctx).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ctx.getColor(R.color.text))
                setTypeface(typeface, Typeface.BOLD)
            }
            subView = TextView(ctx, null, 0, R.style.Small).apply {
                text = subtitle
                setPadding(0, dp(ctx, 2), 0, 0)
            }
            labels.addView(titleView)
            labels.addView(subView)
            addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevronView(ctx))
            setOnClickListener { onClick() }
        }
        return NavRowHandles(row, titleView, subView)
    }

    data class ActionRowHandles(
        val root: LinearLayout,
        val subtitleView: TextView,
        val button: Button,
    )

    fun actionRow(
        ctx: Context,
        title: String,
        subtitle: String,
        buttonLabel: String,
        danger: Boolean = false,
        onClick: () -> Unit,
    ): ActionRowHandles {
        lateinit var subView: TextView
        val btnStyle = if (danger) R.style.Btn_Danger else R.style.Btn_Ghost
        val btn = Button(ctx, null, 0, btnStyle).apply {
            text = buttonLabel
            minHeight = dp(ctx, 40)
            setOnClickListener { onClick() }
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, 56)
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 10))
            val labels = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(
                TextView(ctx).apply {
                    text = title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(ctx.getColor(R.color.text))
                    setTypeface(typeface, Typeface.BOLD)
                },
            )
            subView = TextView(ctx, null, 0, R.style.Small).apply {
                text = subtitle
                setPadding(0, dp(ctx, 2), 0, 0)
            }
            labels.addView(subView)
            addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(ctx, 10)
            })
            addView(btn)
        }
        return ActionRowHandles(row, subView, btn)
    }

    fun segmentedRow(
        ctx: Context,
        title: String,
        subtitle: String? = null,
        labels: List<String>,
        selectedIndex: Int,
        onPick: (Int) -> Unit,
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(ctx, 10), 0, dp(ctx, 10))
        addView(
            TextView(ctx).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ctx.getColor(R.color.text))
                setTypeface(typeface, Typeface.BOLD)
            },
        )
        if (!subtitle.isNullOrBlank()) {
            addView(
                TextView(ctx, null, 0, R.style.Small).apply {
                    text = subtitle
                    setPadding(0, dp(ctx, 2), 0, dp(ctx, 6))
                },
            )
        }
        val track = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.segmented_track)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { if (subtitle.isNullOrBlank()) topMargin = dp(ctx, 6) }
        }
        val buttons = mutableListOf<Button>()
        fun paintSelected(active: Int) {
            buttons.forEachIndexed { idx, btn ->
                btn.isSelected = idx == active
                btn.setTextColor(ctx.getColor(if (idx == active) R.color.accent else R.color.text2))
                btn.setBackgroundResource(if (idx == active) R.drawable.chip_selected_background else 0)
            }
        }
        labels.forEachIndexed { idx, label ->
            val btn = Button(ctx, null, 0, R.style.Segment).apply {
                text = label
                minHeight = dp(ctx, 34)
                setOnClickListener {
                    paintSelected(idx)
                    onPick(idx)
                }
            }
            buttons += btn
            track.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        paintSelected(selectedIndex.coerceIn(labels.indices))
        addView(track)
    }

    data class DisclosureHandles(
        val root: LinearLayout,
        val subtitleView: TextView,
        val body: LinearLayout,
    )

    fun disclosureCard(
        ctx: Activity,
        title: String,
        subtitle: String,
        buildBody: (LinearLayout) -> Unit,
    ): DisclosureHandles {
        val card = cardGroup(ctx)
        lateinit var subView: TextView
        lateinit var chevron: ImageView
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 10))
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, 56)
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 10))
            isClickable = true
            isFocusable = true
            background = ctx.getDrawable(selectableBg(ctx))
            val labels = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(
                TextView(ctx).apply {
                    text = title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(ctx.getColor(R.color.text))
                    setTypeface(typeface, Typeface.BOLD)
                },
            )
            subView = TextView(ctx, null, 0, R.style.Small).apply {
                text = subtitle
                setPadding(0, dp(ctx, 2), 0, 0)
            }
            labels.addView(subView)
            addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            chevron = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = ctx.getColorStateList(R.color.text3)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(chevron, LinearLayout.LayoutParams(dp(ctx, 18), dp(ctx, 18)))
            setOnClickListener {
                val expand = body.visibility != View.VISIBLE
                body.visibility = if (expand) View.VISIBLE else View.GONE
                chevron.setImageResource(if (expand) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right)
            }
        }
        card.addView(header)
        buildBody(body)
        card.addView(body)
        return DisclosureHandles(card, subView, body)
    }

    private fun iconBadge(ctx: Context, iconRes: Int): ImageView =
        ImageView(ctx).apply {
            setImageResource(iconRes)
            setBackgroundResource(R.drawable.settings_icon_badge)
            imageTintList = ctx.getColorStateList(R.color.accent)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    private fun badgeLayoutParams(ctx: Context): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36)).apply {
            marginEnd = dp(ctx, 12)
        }

    private fun chevronView(ctx: Context): ImageView =
        ImageView(ctx).apply {
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = ctx.getColorStateList(R.color.text3)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 18), dp(ctx, 18)).apply {
                marginStart = dp(ctx, 8)
            }
        }

    private fun titleSubtitleColumn(ctx: Context, title: String, subtitle: String): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(ctx, 10), 0)
            addView(
                TextView(ctx).apply {
                    text = title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(ctx.getColor(R.color.text))
                    setTypeface(typeface, Typeface.BOLD)
                },
            )
            addView(
                TextView(ctx, null, 0, R.style.Small).apply {
                    text = subtitle
                    setPadding(0, dp(ctx, 2), 0, 0)
                },
            )
        }
}
