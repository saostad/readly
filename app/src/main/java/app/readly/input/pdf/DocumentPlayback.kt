package app.readly.input.pdf

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.readly.R
import app.readly.ReadlyApp
import app.readly.playback.ReaderService
import java.io.File

/** Shared document replay for Library, settings, and other local clients. */
object DocumentPlayback {
    data class Prepared(
        val file: File,
        val title: String,
        val notice: String,
        val documentId: String = "",
        val startPage: Int = 0,
    )

    /** Validates state and current limits before producing a bounded, private text handoff. */
    fun prepare(context: Context, id: String, startPage: Int = 0): Prepared = PdfImportStore(context).use { store ->
        val item = store.find(id)
            ?: throw PdfImportException("This document is no longer available. Open the original file again.")
        if (item.status != "ready") throw PdfImportException("This document is not ready to read. Open the original file again.")
        Prepared(store.textFile(id, PdfFiles.limits(context)), item.title, item.notice, id, startPage)
    }

    /** Starts playback without repeating OCR. The service consumes the private file. */
    fun start(context: Context, prepared: Prepared) {
        try {
            ReaderService.readPdf(context, prepared.file.name, prepared.title, prepared.notice, prepared.documentId, prepared.startPage)
        } catch (error: Exception) {
            Log.e("Readly", "Couldn't start saved document playback", error)
            if (prepared.file.exists() && !prepared.file.delete()) Log.w("Readly", "Couldn't remove failed document handoff")
            throw error
        }
    }

    fun read(activity: Activity, id: String, startPage: Int = 0) {
        (activity.application as ReadlyApp).ioExecutor.execute {
            try {
                val prepared = prepare(activity, id, startPage)
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) {
                        if (!prepared.file.delete()) Log.w("Readly", "Couldn't remove abandoned document handoff")
                    } else {
                        try { start(activity, prepared) }
                        catch (error: Exception) { showError(activity, error) }
                    }
                }
            } catch (error: Exception) { showError(activity, error) }
        }
    }

    /** Prompts for a 1-based starting page and starts playback for saved document [id]. */
    fun promptStartPage(activity: Activity, id: String) {
        (activity.application as ReadlyApp).ioExecutor.execute {
            try {
                val item = PdfImportStore(activity).use { it.find(id) }
                    ?: throw PdfImportException("This document is no longer available. Open the original file again.")
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    if (item.status != "ready") {
                        Toast.makeText(activity, R.string.pdf_imports_unready, Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    showPagePickerDialog(
                        activity,
                        item.title,
                        item.pageCount,
                        item.lastPage.takeIf { it > 0 } ?: 1,
                    ) { page -> read(activity, item.id, startPage = page) }
                }
            } catch (error: Exception) { showError(activity, error) }
        }
    }

    /** Shows a numeric page-picker dialog and invokes [onPageSelected] when a valid page is entered. */
    fun showPagePickerDialog(
        activity: Activity,
        title: String,
        totalPages: Int,
        initialPage: Int,
        onPageSelected: (Int) -> Unit,
    ) {
        val density = activity.resources.displayMetrics.density
        val padH = (20 * density).toInt()
        val padV = (12 * density).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padV, padH, 0)
        }
        val subtitle = if (totalPages > 0) {
            activity.getString(R.string.pdf_start_from_page_range, title, totalPages)
        } else {
            title
        }
        box.addView(TextView(activity, null, 0, R.style.Small).apply {
            text = subtitle
        })
        val input = EditText(activity, null, 0, R.style.BoxEdit).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(initialPage.coerceAtLeast(1).let { if (totalPages > 0) it.coerceAtMost(totalPages) else it }.toString())
            setSelectAllOnFocus(true)
            if (totalPages > 0) hint = activity.getString(R.string.pdf_start_from_page_input_label, totalPages)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (10 * density).toInt() }
        }
        box.addView(input)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.pdf_start_from_page_title)
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val entered = input.text.toString().trim().toIntOrNull()
            val valid = entered != null && entered >= 1 && (totalPages <= 0 || entered <= totalPages)
            if (!valid) {
                val maxLabel = if (totalPages > 0) totalPages else 1
                Toast.makeText(activity, activity.getString(R.string.pdf_start_from_page_invalid, maxLabel), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            onPageSelected(entered!!)
        }
    }

    private fun showError(activity: Activity, error: Exception) {
        Log.e("Readly", "Saved document replay failed", error)
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) Toast.makeText(activity,
                if (error is PdfImportException) error.message else activity.getString(R.string.document_operation_failed),
                Toast.LENGTH_LONG).show()
        }
    }
}
