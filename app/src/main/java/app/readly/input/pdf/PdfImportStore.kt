package app.readly.input.pdf

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.util.UUID

/** Durable inputs, step state and immutable page drafts; callers share normalize-and-save. */
class PdfImportStore(private val context: Context) : SQLiteOpenHelper(context, "pdf-imports.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE imports (id TEXT PRIMARY KEY, title TEXT NOT NULL, status TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'pdf', created INTEGER NOT NULL, notice TEXT, error TEXT)")
        db.execSQL("CREATE TABLE pages (import_id TEXT NOT NULL, page INTEGER NOT NULL, text TEXT NOT NULL, source TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'ready', model TEXT, confidence INTEGER, PRIMARY KEY(import_id,page))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) {
            db.execSQL("ALTER TABLE imports ADD COLUMN kind TEXT NOT NULL DEFAULT 'pdf'")
        } else error("Document import database migration is not available: $oldVersion to $newVersion")
    }

    private fun valid(id: String) { require(UUID.fromString(id).toString() == id) { "Invalid PDF import id" } }
    fun sourceFile(id: String, kind: String = "pdf"): File {
        valid(id)
        require(kind in listOf("pdf", "image")) { "Unsupported document kind" }
        val dir = File(context.filesDir, "pdf-imports/$id")
        if (!dir.isDirectory && !dir.mkdirs()) throw PdfImportException("Not enough storage to import this document. Free some space and try again.")
        return File(dir, if (kind == "image") "source.image" else "source.pdf")
    }
    fun create(id: String, title: String, kind: String = "pdf") {
        valid(id)
        require(kind in listOf("pdf", "image")) { "Unsupported document kind" }
        writableDatabase.insertOrThrow("imports", null, ContentValues().apply {
            put("id", id); put("title", title); put("kind", kind); put("status", "copying"); put("created", System.currentTimeMillis())
        })
    }
    fun status(id: String, status: String, notice: String? = null, error: String? = null) {
        valid(id)
        require(status in listOf("copying", "extracting", "recognizing", "ready", "failed", "cancelled"))
        val changed = writableDatabase.update("imports", ContentValues().apply {
            put("status", status); put("notice", notice); put("error", error)
        }, "id=?", arrayOf(id))
        check(changed == 1) { "PDF import record is missing" }
    }
    fun savePage(id: String, draft: PdfPageDraft, limits: PdfLimits) {
        valid(id); limits.validate()
        require(draft.page >= 0)
        require(draft.source != PdfPageSource.OCR || !draft.modelId.isNullOrBlank())
        val text = PdfText.normalizePage(draft.text)
        require(text.length <= limits.maxTextChars)
        // No replace/upsert: an agent or a second client cannot silently overwrite a saved page.
        writableDatabase.insertOrThrow("pages", null, ContentValues().apply {
            put("import_id", id); put("page", draft.page); put("text", text)
            put("source", draft.source.name); put("model", draft.modelId)
            if (draft.confidence != null) put("confidence", draft.confidence)
        })
    }
    fun recoverInterrupted(): Int = writableDatabase.update("imports", ContentValues().apply {
        put("status", "failed"); put("error", "Import interrupted. Share the document again.")
    }, "status IN ('copying','extracting','recognizing')", null)

    data class Import(val id: String, val title: String, val status: String, val notice: String, val kind: String)
    fun list(): List<Import> = readableDatabase.query("imports", arrayOf("id", "title", "status", "notice", "kind"),
        null, null, null, null, "created DESC").use { cursor ->
        buildList { while (cursor.moveToNext()) add(Import(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3).orEmpty(), cursor.getString(4))) }
    }
    fun search(query: String): List<app.readly.library.LibraryResult> {
        return readableDatabase.rawQuery("SELECT i.id,i.title,i.kind,p.text FROM imports i LEFT JOIN pages p ON p.import_id=i.id WHERE i.status='ready' ORDER BY i.created DESC,p.page ASC", null).use { c ->
            val found = linkedMapOf<String, app.readly.library.LibraryResult>()
            while (c.moveToNext()) {
                if (app.readly.library.LibraryMatch.matches(query, c.getString(1), c.getString(3).orEmpty())) {
                    val id = c.getString(0)
                    found.putIfAbsent(id, app.readly.library.LibraryResult("document:$id", c.getString(1), "${c.getString(2)} · Saved document", documentId = id))
                }
            }
            found.values.toList()
        }
    }

    fun textFile(id: String, limits: PdfLimits): File {
        valid(id); limits.validate()
        require(list().any { it.id == id && it.status == "ready" }) { "This document is not ready. Share the file again." }
        val target = PdfFiles.newText(context)
        var success = false
        try {
            target.bufferedWriter().use { output ->
                readableDatabase.query("pages", arrayOf("text"), "import_id=?", arrayOf(id), null, null, "page ASC").use { cursor ->
                    var written = 0
                    while (cursor.moveToNext()) {
                        val text = cursor.getString(0)
                        if (text.isBlank()) continue
                        val separator = if (written == 0) "" else "\n\n"
                        if (text.length + separator.length > limits.maxTextChars - written) throw PdfImportException("Saved document exceeds the text limit. Increase the document text limit and try again.")
                        output.write(separator); output.write(text); written += separator.length + text.length
                    }
                }
            }
            success = true
            return target
        } finally {
            if (!success && target.exists() && !target.delete()) android.util.Log.w("Readly", "Couldn't remove failed PDF replay file")
        }
    }
    /** Explicit user action. Never delete a document that is still being processed. */
    fun clearFinished(): Int {
        val finished = list().filter { it.status in listOf("ready", "failed", "cancelled") }
        for (item in finished) {
            val dir = sourceFile(item.id).parentFile!!
            if (!dir.deleteRecursively()) throw PdfImportException("Couldn't remove an imported document. Try clearing it again.")
            writableDatabase.beginTransaction()
            try {
                writableDatabase.delete("pages", "import_id=?", arrayOf(item.id))
                writableDatabase.delete("imports", "id=?", arrayOf(item.id))
                writableDatabase.setTransactionSuccessful()
            } finally { writableDatabase.endTransaction() }
        }
        return finished.size
    }
}
