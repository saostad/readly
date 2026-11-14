package app.readly.ui

import android.app.Activity
import android.app.AlertDialog
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import app.readly.ReadlyApp
import app.readly.library.Bookmark
import app.readly.library.ReadingStore
import app.readly.playback.ReaderSnapshot

object BookmarkDialogs {
    fun add(activity: Activity, snapshot: ReaderSnapshot) {
        val editor = EditText(activity).apply { hint = "Note (optional)"; minLines = 2; setText("") }
        val dialog = AlertDialog.Builder(activity).setTitle("Bookmark this sentence")
            .setMessage(snapshot.currentText).setView(editor).setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                val note = editor.text.toString()
                (activity.application as ReadlyApp).ioExecutor.execute {
                    try {
                        ReadingStore(activity).use { it.addBookmark(snapshot.readingId, snapshot.index, note) }
                        activity.runOnUiThread {
                            if (!activity.isDestroyed) { dialog.dismiss(); Toast.makeText(activity, "Bookmark saved", Toast.LENGTH_SHORT).show() }
                        }
                    } catch (error: Exception) {
                        Log.e("Readly", "Couldn't save bookmark reading=${snapshot.readingId} sentence=${snapshot.index}", error)
                        activity.runOnUiThread {
                            if (!activity.isDestroyed) { editor.error = "Couldn't save the bookmark. Free some storage and try again."; dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true }
                        }
                    }
                }
            }
        }
        dialog.show()
    }
    fun edit(activity: Activity, bookmark: Bookmark, onSaved: () -> Unit) {
        val editor = EditText(activity).apply { hint = "Note"; minLines = 2; setText(bookmark.note) }
        val dialog = AlertDialog.Builder(activity).setTitle("Edit bookmark note").setMessage(bookmark.quote)
            .setView(editor).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                val text = editor.text.toString()
                (activity.application as ReadlyApp).ioExecutor.execute {
                    try {
                        ReadingStore(activity).use { it.updateBookmark(bookmark.id, bookmark.version, text) }
                        activity.runOnUiThread { if (!activity.isDestroyed) { dialog.dismiss(); onSaved() } }
                    } catch (error: Exception) {
                        Log.e("Readly", "Couldn't update bookmark id=${bookmark.id}", error)
                        activity.runOnUiThread { if (!activity.isDestroyed) {
                            editor.error = "Couldn't save. This bookmark may have changed; reopen it and try again."
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        } }
                    }
                }
            }
        }
        dialog.show()
    }
}
