package app.readly.ui

import android.app.Activity
import android.app.AlertDialog
import android.util.Log
import android.text.InputType
import android.widget.*
import app.readly.R
import app.readly.ReadlyApp
import app.readly.library.*

object ArticleFilterDialog {
    fun show(activity: Activity, onSaved: () -> Unit) {
        val app = activity.application as ReadlyApp
        app.ioExecutor.execute {
            try {
                val current = app.feeds.filter()
                val sites = listOf("") + (app.feeds.sites().map { it.label() } + current.site).filter { it.isNotBlank() }.distinct().sorted()
                val languages = listOf("") + (app.feeds.articleFacts().values.map { it.language } + current.language).filter { it.isNotBlank() }.distinct().sorted()
                activity.runOnUiThread { if (!activity.isDestroyed && !activity.isFinishing) build(activity, current, sites, languages, onSaved) }
            } catch (error: Exception) {
                Log.e("Readly", "Couldn't load article filter settings", error)
                activity.runOnUiThread { if (!activity.isDestroyed) Toast.makeText(activity, "Couldn't load filters. Reopen the list and try again.", Toast.LENGTH_LONG).show() }
            }
        }
    }
    private fun build(activity: Activity, current: ArticleFilter, sites: List<String>, languages: List<String>, onSaved: () -> Unit) {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        fun label(value: String) { box.addView(TextView(activity).apply { text = value; setTextColor(activity.getColor(R.color.text2)) }) }
        fun spinner(label: String, values: List<String>, selected: Int): Spinner {
            label(label)
            return Spinner(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, values)
                setSelection(selected); box.addView(this)
            }
        }
        val unheard = Switch(activity).apply { text = "Unheard only"; isChecked = current.unheard; box.addView(this) }
        val site = spinner("Website", sites.map { it.ifBlank { "All websites" } }, sites.indexOf(current.site))
        val language = spinner("Language", languages.map { it.ifBlank { "All languages" } }, languages.indexOf(current.language))
        val sort = spinner("Sort by", ArticleSort.entries.map { it.label }, ArticleSort.entries.indexOf(current.sort))
        fun number(title: String, value: String): EditText {
            label(title)
            return EditText(activity).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(value); box.addView(this) }
        }
        val minutes = number("Maximum listening time in minutes (blank = any)", current.maxMinutes?.toString().orEmpty())
        val pace = number("Estimated reading pace, words per minute", current.wordsPerMinute.toString())
        label("Language and length are known after opening an article. Those filters exclude unknown articles. Missing publication dates sort last. Estimates use the pace above.")
        val error = TextView(activity).apply { setTextColor(activity.getColor(R.color.error)); box.addView(this) }
        val dialog = AlertDialog.Builder(activity).setTitle("Filters & sort").setView(ScrollView(activity).apply { addView(box) })
            .setNegativeButton("Cancel", null).setNeutralButton("Reset", null).setPositiveButton("Apply", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                unheard.isChecked = false; site.setSelection(0); language.setSelection(0); sort.setSelection(0)
                minutes.setText(""); pace.setText(ArticleFilter.DEFAULT_WORDS_PER_MINUTE.toString())
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val max = minutes.text.toString().trim()
                    require(max.isBlank() || max.toIntOrNull() != null) { "Enter a valid number of minutes." }
                    val filter = ArticleFilter(unheard.isChecked, sites[site.selectedItemPosition], languages[language.selectedItemPosition], max.toIntOrNull(),
                        pace.text.toString().toIntOrNull() ?: 0, ArticleSort.entries[sort.selectedItemPosition])
                    filter.validate()
                    (activity.application as ReadlyApp).feeds.saveFilter(filter)
                    dialog.dismiss(); onSaved()
                } catch (failure: Exception) {
                    Log.e("Readly", "Couldn't save article filters", failure)
                    error.text = if (failure is IllegalArgumentException) failure.message else "Couldn't save the filters. Try again."
                }
            }
        }
        dialog.show()
    }
}
