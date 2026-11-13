package app.readly.input.pdf

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.Toast
import app.readly.R
import app.readly.ReadlyApp
import app.readly.playback.ReaderService
import java.io.File

/** Shared document replay for Library, settings, and other local clients. */
object DocumentPlayback {
    data class Prepared(val file: File, val title: String, val notice: String)

    /** Validates state and current limits before producing a bounded, private text handoff. */
    fun prepare(context: Context, id: String): Prepared = PdfImportStore(context).use { store ->
        val item = store.list().firstOrNull { it.id == id }
            ?: throw PdfImportException("This document is no longer available. Open the original file again.")
        if (item.status != "ready") throw PdfImportException("This document is not ready to read. Open the original file again.")
        Prepared(store.textFile(id, PdfFiles.limits(context)), item.title, item.notice)
    }

    /** Starts playback without repeating OCR. The service consumes the private file. */
    fun start(context: Context, prepared: Prepared) {
        try {
            ReaderService.readPdf(context, prepared.file.name, prepared.title, prepared.notice)
        } catch (error: Exception) {
            Log.e("Readly", "Couldn't start saved document playback", error)
            if (prepared.file.exists() && !prepared.file.delete()) Log.w("Readly", "Couldn't remove failed document handoff")
            throw error
        }
    }

    fun read(activity: Activity, id: String) {
        (activity.application as ReadlyApp).ioExecutor.execute {
            try {
                val prepared = prepare(activity, id)
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

    private fun showError(activity: Activity, error: Exception) {
        Log.e("Readly", "Saved document replay failed", error)
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) Toast.makeText(activity,
                if (error is PdfImportException) error.message else activity.getString(R.string.document_operation_failed),
                Toast.LENGTH_LONG).show()
        }
    }
}
