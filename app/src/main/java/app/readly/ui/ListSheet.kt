package app.readly.ui

import android.app.Activity
import android.app.Dialog
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import app.readly.R

/**
 * A bottom-sheet list picker: a title, an optional search field and a
 * single-choice list. [onPick] gets the index into [options].
 */
class ListSheet(
    private val activity: Activity,
    private val title: String,
    private val options: List<String>,
    private val selected: Int,
    private val withSearch: Boolean,
    private val onPick: (Int) -> Unit,
) {

    private fun dp(n: Int): Int = (n * activity.resources.displayMetrics.density + 0.5f).toInt()

    fun show() {
        val dialog = Dialog(activity, R.style.SheetDialog)
        dialog.asBottomSheet() ?: return
        val pad = dp(16)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(10), pad, pad)
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
            TextView(activity, null, 0, R.style.CardTitle).apply { text = title },
        )
        val adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_list_item_single_choice,
            ArrayList(options),
        )
        if (withSearch) {
            root.addView(
                EditText(activity, null, 0, R.style.BoxEdit).apply {
                    hint = activity.getString(R.string.search_hint)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(10) }
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int,
                        ) = Unit

                        override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int,
                        ) = Unit

                        override fun afterTextChanged(s: Editable?) {
                            adapter.filter.filter(s?.toString().orEmpty())
                        }
                    })
                },
            )
        }
        root.addView(
            ListView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(320),
                ).apply { topMargin = dp(10) }
                choiceMode = ListView.CHOICE_MODE_SINGLE
                this.adapter = adapter
                setItemChecked(selected, true)
                setSelection(selected.coerceAtLeast(0))
                setOnItemClickListener { _, _, position, _ ->
                    val value = adapter.getItem(position)
                    val index = options.indexOf(value)
                    dialog.dismiss()
                    if (index >= 0) onPick(index)
                }
            },
        )
        dialog.setContentView(root)
        dialog.show()
        Sheets.applyFullWidth(dialog)
    }
}
