package app.readly.ui

import android.app.Activity
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.readly.R
import kotlin.math.max
import kotlin.math.min

/**
 * Clean bottom sheets triggered by Home's `+` button:
 * - [showAddMenu]: unified entry point for Open file, Paste text/link, and Add website.
 * - [showPasteDialog]: focused paste/compose dialog replacing the permanent inline card.
 */
object AddContentSheets {

    fun showAddMenu(
        activity: Activity,
        onOpenFile: () -> Unit,
        onPasteText: () -> Unit,
        onAddWebsite: () -> Unit,
    ) {
        val dialog = Dialog(activity, R.style.SheetDialog)
        dialog.asBottomSheet() ?: return
        val dp = { n: Int -> SettingsWidgets.dp(activity, n) }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(18))
        }
        root.addView(
            View(activity).apply {
                background = activity.getDrawable(R.drawable.grab_handle)
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(12)
                }
            },
        )
        root.addView(
            TextView(activity, null, 0, R.style.CardTitle).apply {
                text = activity.getString(R.string.add_sheet_title)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(12) }
            },
        )

        fun actionCard(iconRes: Int, title: String, subtitle: String, onClick: () -> Unit) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(60)
                setBackgroundResource(R.drawable.settings_row_card)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
                addView(
                    ImageView(activity).apply {
                        setImageResource(iconRes)
                        setBackgroundResource(R.drawable.settings_icon_badge)
                        imageTintList = activity.getColorStateList(R.color.accent)
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) },
                )
                val labels = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                labels.addView(
                    TextView(activity).apply {
                        text = title
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                        setTextColor(activity.getColor(R.color.text))
                        setTypeface(typeface, Typeface.BOLD)
                    },
                )
                labels.addView(
                    TextView(activity, null, 0, R.style.Small).apply {
                        text = subtitle
                        setPadding(0, dp(2), 0, 0)
                    },
                )
                addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
            }
            root.addView(row)
        }

        actionCard(
            R.drawable.ic_folder_open,
            activity.getString(R.string.open_file_button),
            activity.getString(R.string.add_open_file_sub),
            onOpenFile,
        )
        actionCard(
            R.drawable.ic_text,
            activity.getString(R.string.paste_text_button),
            activity.getString(R.string.add_paste_sub),
            onPasteText,
        )
        actionCard(
            R.drawable.ic_globe,
            activity.getString(R.string.add_website_title),
            activity.getString(R.string.add_website_sub),
            onAddWebsite,
        )

        dialog.setContentView(root)
        dialog.show()
        Sheets.applyFullWidth(dialog)
    }

    /** Shows the focused compose/paste sheet; returns true from [onSubmit] when handled. */
    fun showPasteDialog(
        activity: Activity,
        onSubmit: (String) -> Boolean,
    ) {
        val dialog = Dialog(activity, R.style.SheetDialog)
        val window = dialog.asBottomSheet() ?: return
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val dp = { n: Int -> SettingsWidgets.dp(activity, n) }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(18))
        }
        root.addView(
            View(activity).apply {
                background = activity.getDrawable(R.drawable.grab_handle)
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(12)
                }
            },
        )
        root.addView(
            TextView(activity, null, 0, R.style.CardTitle).apply {
                text = activity.getString(R.string.home_card_title)
            },
        )
        root.addView(
            TextView(activity, null, 0, R.style.Small).apply {
                text = activity.getString(R.string.home_card_subtitle)
                setPadding(0, dp(4), 0, dp(10))
            },
        )
        val input = EditText(activity, null, 0, R.style.BoxEdit).apply {
            hint = activity.getString(R.string.input_hint)
            gravity = Gravity.TOP
            minLines = 4
            maxLines = 8
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        root.addView(input)

        val errorView = TextView(activity, null, 0, R.style.Small).apply {
            setText(R.string.input_error)
            setTextColor(activity.getColor(R.color.error))
            setPadding(0, dp(6), 0, 0)
            visibility = View.GONE
        }
        root.addView(errorView)

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }
        val pasteBtn = Button(activity, null, 0, R.style.Btn_Ghost).apply {
            setText(R.string.paste_button)
            setOnClickListener {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(activity)
                    ?.toString()
                    .orEmpty()
                if (clip.isBlank()) {
                    Toast.makeText(activity, R.string.nothing_to_paste, Toast.LENGTH_SHORT).show()
                } else {
                    val start = min(input.selectionStart, input.selectionEnd).coerceAtLeast(0)
                    val end = max(input.selectionStart, input.selectionEnd).coerceAtLeast(0)
                    input.text.replace(start, end, clip)
                    errorView.visibility = View.GONE
                }
            }
        }
        val readBtn = Button(activity, null, 0, R.style.Btn_Primary).apply {
            setText(R.string.read_button)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play, 0, 0, 0)
            compoundDrawablePadding = dp(8)
            compoundDrawableTintList = activity.getColorStateList(R.color.onAccent)
            setOnClickListener {
                val value = input.text.toString()
                if (onSubmit(value)) {
                    dialog.dismiss()
                } else {
                    errorView.visibility = View.VISIBLE
                }
            }
        }
        actions.addView(pasteBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(
            readBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f).apply {
                marginStart = dp(8)
            },
        )
        root.addView(actions)

        dialog.setContentView(root)
        dialog.show()
        Sheets.applyFullWidth(dialog)
        input.requestFocus()
    }
}
