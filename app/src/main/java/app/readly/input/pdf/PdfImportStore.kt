package app.readly.input.pdf

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.File
import java.util.UUID

/** Durable inputs, step state and immutable page drafts; callers share normalize-and-save. */
class PdfImportStore(private val context: Context) : SQLiteOpenHelper(context, "pdf-imports.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE imports (" +
                "id TEXT PRIMARY KEY, " +
                "title TEXT NOT NULL, " +
                "status TEXT NOT NULL, " +
                "kind TEXT NOT NULL DEFAULT 'pdf', " +
                "created INTEGER NOT NULL, " +
                "notice TEXT, " +
                "error TEXT, " +
                "sha256 TEXT, " +
                "last_page INTEGER NOT NULL DEFAULT 0, " +
                "last_sentence INTEGER NOT NULL DEFAULT 0, " +
                "last_page_sentence INTEGER NOT NULL DEFAULT 0, " +
                "last_offset INTEGER NOT NULL DEFAULT 0, " +
                "total_sentences INTEGER NOT NULL DEFAULT 0, " +
                "finished INTEGER NOT NULL DEFAULT 0" +
                ")",
        )
        db.execSQL("CREATE TABLE pages (import_id TEXT NOT NULL, page INTEGER NOT NULL, text TEXT NOT NULL, source TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'ready', model TEXT, confidence INTEGER, PRIMARY KEY(import_id,page))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1 || newVersion > 3 || oldVersion >= newVersion) {
            error("Document import database migration is not available: $oldVersion to $newVersion")
        }
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE imports ADD COLUMN kind TEXT NOT NULL DEFAULT 'pdf'")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE imports ADD COLUMN sha256 TEXT")
            db.execSQL("ALTER TABLE imports ADD COLUMN last_page INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE imports ADD COLUMN last_sentence INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE imports ADD COLUMN last_page_sentence INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE imports ADD COLUMN last_offset INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE imports ADD COLUMN total_sentences INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE imports ADD COLUMN finished INTEGER NOT NULL DEFAULT 0")
        }
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
    fun setSha256(id: String, sha256: String) {
        valid(id)
        require(sha256.isNotBlank()) { "Document hash is required" }
        val changed = writableDatabase.update("imports", ContentValues().apply { put("sha256", sha256) }, "id=?", arrayOf(id))
        check(changed == 1) { "PDF import record is missing" }
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

    data class Import(
        val id: String,
        val title: String,
        val status: String,
        val notice: String,
        val kind: String,
        val lastPage: Int = 0,
        val lastSentence: Int = 0,
        val lastPageSentence: Int = 0,
        val lastOffsetChars: Int = 0,
        val totalSentences: Int = 0,
        val finished: Boolean = false,
        val pageCount: Int = 0,
    ) {
        val progress: PdfReadingProgress
            get() = PdfReadingProgress(lastPage, lastSentence, lastPageSentence, lastOffsetChars, totalSentences, finished)

        fun progressLabel(): String? = when {
            status != "ready" -> null
            finished -> "Finished"
            (lastSentence > 0 || lastOffsetChars > 0) && totalSentences > 0 ->
                if (kind == "image") "Sentence ${lastSentence + 1} of $totalSentences"
                else if (pageCount > 0) "Page $lastPage of $pageCount · Sentence ${lastSentence + 1} of $totalSentences"
                else "Page $lastPage · Sentence ${lastSentence + 1} of $totalSentences"
            (lastSentence > 0 || lastOffsetChars > 0) && lastPage > 0 && kind != "image" ->
                if (pageCount > 0) "Page $lastPage of $pageCount" else "Page $lastPage"
            kind == "pdf" && pageCount > 0 ->
                if (pageCount == 1) "1 page" else "$pageCount pages"
            else -> null
        }
    }

    private fun readImport(cursor: Cursor): Import = Import(
        id = cursor.getString(0),
        title = cursor.getString(1),
        status = cursor.getString(2),
        notice = cursor.getString(3).orEmpty(),
        kind = cursor.getString(4),
        lastPage = cursor.getInt(5),
        lastSentence = cursor.getInt(6),
        lastPageSentence = cursor.getInt(7),
        lastOffsetChars = cursor.getInt(8),
        totalSentences = cursor.getInt(9),
        finished = cursor.getInt(10) != 0,
        pageCount = cursor.getInt(11),
    )

    fun list(): List<Import> = readableDatabase.query(
        "imports",
        IMPORT_COLUMNS,
        null, null, null, null, "created DESC",
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(readImport(cursor)) }
    }

    fun find(id: String): Import? {
        valid(id)
        return readableDatabase.query("imports", IMPORT_COLUMNS, "id=?", arrayOf(id), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) readImport(cursor) else null
        }
    }

    /** Finds an existing ready import matching [sha256] and [kind], backfilling hashes on legacy rows. */
    fun findReadyBySha256(sha256: String, kind: String, byteLength: Long = -1L): Import? {
        require(sha256.isNotBlank()) { "Document hash is required" }
        require(kind in listOf("pdf", "image")) { "Unsupported document kind" }
        val candidates = readableDatabase.query(
            "imports",
            arrayOf("id", "sha256"),
            "status='ready' AND kind=?",
            arrayOf(kind),
            null, null, "created DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0) to cursor.getString(1))
                }
            }
        }
        for ((id, stored) in candidates) {
            if (stored == sha256 && pages(id).any { PdfText.hasReadableText(it.text) }) {
                return find(id)
            }
        }
        for ((id, stored) in candidates) {
            if (stored != null) continue
            val file = sourceFile(id, kind)
            if (!file.isFile) continue
            if (byteLength >= 0 && file.length() != byteLength) continue
            val computed = try {
                PdfFiles.sha256(file)
            } catch (e: Exception) {
                Log.w("Readly", "Couldn't hash stored document $id", e)
                continue
            }
            setSha256(id, computed)
            if (computed == sha256 && pages(id).any { PdfText.hasReadableText(it.text) }) {
                return find(id)
            }
        }
        return null
    }

    fun pages(id: String): List<PdfPageText> {
        valid(id)
        return readableDatabase.query(
            "pages",
            arrayOf("page", "text"),
            "import_id=?",
            arrayOf(id),
            null, null, "page ASC",
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(PdfPageText(cursor.getInt(0), cursor.getString(1))) }
        }
    }

    fun progress(id: String): PdfReadingProgress? = find(id)?.progress

    fun recordProgress(id: String, location: PdfChunkLocation, totalSentences: Int) {
        valid(id)
        require(location.page >= 1 && location.chunkIndex >= 0 && totalSentences > 0)
        val changed = writableDatabase.update("imports", ContentValues().apply {
            put("last_page", location.page)
            put("last_sentence", location.chunkIndex)
            put("last_page_sentence", location.pageSentenceIndex)
            put("last_offset", location.offsetChars)
            put("total_sentences", totalSentences)
            put("finished", 0)
        }, "id=?", arrayOf(id))
        check(changed == 1) { "PDF import record is missing" }
    }

    fun markFinished(id: String) {
        valid(id)
        val changed = writableDatabase.update("imports", ContentValues().apply {
            put("finished", 1)
            put("last_page", 0)
            put("last_sentence", 0)
            put("last_page_sentence", 0)
            put("last_offset", 0)
        }, "id=?", arrayOf(id))
        check(changed == 1) { "PDF import record is missing" }
    }

    fun search(query: String): List<app.readly.library.LibraryResult> {
        val byId = list().associateBy { it.id }
        return readableDatabase.rawQuery("SELECT i.id,i.title,i.kind,p.text FROM imports i LEFT JOIN pages p ON p.import_id=i.id WHERE i.status='ready' ORDER BY i.created DESC,p.page ASC", null).use { c ->
            val found = linkedMapOf<String, app.readly.library.LibraryResult>()
            while (c.moveToNext()) {
                if (app.readly.library.LibraryMatch.matches(query, c.getString(1), c.getString(3).orEmpty())) {
                    val id = c.getString(0)
                    val progress = byId[id]?.progressLabel()
                    val detail = listOfNotNull(c.getString(2), "Saved document", progress).joinToString(" · ")
                    found.putIfAbsent(id, app.readly.library.LibraryResult("document:$id", c.getString(1), detail, documentId = id))
                }
            }
            found.values.toList()
        }
    }

    fun writeText(id: String, limits: PdfLimits, target: File) {
        valid(id); limits.validate()
        require(find(id)?.status == "ready") { "This document is not ready. Share the file again." }
        target.bufferedWriter().use { output ->
            readableDatabase.query("pages", arrayOf("text"), "import_id=?", arrayOf(id), null, null, "page ASC").use { cursor ->
                var written = 0
                while (cursor.moveToNext()) {
                    val text = cursor.getString(0)
                    if (!PdfText.hasReadableText(text)) continue
                    val separator = if (written == 0) "" else "\n\n"
                    if (text.length + separator.length > limits.maxTextChars - written) {
                        throw PdfImportException("Saved document exceeds the text limit. Increase the document text limit and try again.")
                    }
                    output.write(separator); output.write(text); written += separator.length + text.length
                }
            }
        }
    }

    fun textFile(id: String, limits: PdfLimits): File {
        valid(id); limits.validate()
        val target = PdfFiles.newText(context)
        var success = false
        try {
            writeText(id, limits, target)
            success = true
            return target
        } finally {
            if (!success && target.exists() && !target.delete()) Log.w("Readly", "Couldn't remove failed PDF replay file")
        }
    }

    /** Removes a temporary/duplicate import directory and its database rows. */
    fun discardImport(id: String, kind: String = "pdf") {
        valid(id)
        require(kind in listOf("pdf", "image")) { "Unsupported document kind" }
        val dir = sourceFile(id, kind).parentFile!!
        if (dir.exists() && !dir.deleteRecursively()) {
            Log.w("Readly", "Couldn't remove discarded document directory $id")
        }
        deleteRows(id)
    }

    private fun deleteRows(id: String) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("pages", "import_id=?", arrayOf(id))
            writableDatabase.delete("imports", "id=?", arrayOf(id))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /** Explicit user action. Never delete a document that is still being processed. */
    fun clearFinished(): Int {
        val finished = list().filter { it.status in listOf("ready", "failed", "cancelled") }
        for (item in finished) {
            val dir = sourceFile(item.id).parentFile!!
            if (!dir.deleteRecursively()) throw PdfImportException("Couldn't remove an imported document. Try clearing it again.")
            deleteRows(item.id)
        }
        return finished.size
    }

    private companion object {
        val IMPORT_COLUMNS = arrayOf(
            "id", "title", "status", "notice", "kind",
            "last_page", "last_sentence", "last_page_sentence", "last_offset", "total_sentences", "finished",
            "COALESCE((SELECT MAX(p.page) + 1 FROM pages p WHERE p.import_id = imports.id), 0)",
        )
    }
}
