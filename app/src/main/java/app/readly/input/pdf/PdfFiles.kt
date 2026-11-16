package app.readly.input.pdf

import android.content.Context
import app.readly.playback.ReaderService
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException

object PdfFiles {
    const val KEY_MAX_MB = "pdfMaxFileMb"
    const val KEY_MAX_CHARS = "pdfMaxTextChars"
    const val KEY_OCR_ENABLED = "pdfOcrEnabled"
    const val KEY_OCR_LANGUAGE = "pdfOcrLanguage"
    const val KEY_OCR_SIDE = "pdfOcrMaxSide"
    const val KEY_IMAGE_LAYOUT = "imageOcrLayout"

    fun ocrOptions(context: Context): PdfOcrOptions {
        val prefs = context.getSharedPreferences(ReaderService.PREFS_NAME, Context.MODE_PRIVATE)
        val defaults = PdfOcrOptions()
        return PdfOcrOptions(prefs.getBoolean(KEY_OCR_ENABLED, defaults.enabled),
            prefs.getString(KEY_OCR_LANGUAGE, defaults.languages) ?: defaults.languages,
            prefs.getInt(KEY_OCR_SIDE, defaults.maxSide),
            prefs.getString(KEY_IMAGE_LAYOUT, defaults.imageLayout) ?: defaults.imageLayout).also { it.validate() }
    }

    fun limits(context: Context): PdfLimits {
        val prefs = context.getSharedPreferences(ReaderService.PREFS_NAME, Context.MODE_PRIVATE)
        return PdfLimits(
            prefs.getInt(KEY_MAX_MB, PdfLimits().maxFileMb),
            prefs.getInt(KEY_MAX_CHARS, PdfLimits().maxTextChars),
        ).also { it.validate() }
    }

    private fun directory(context: Context): File = File(context.cacheDir, "pdf-imports").also {
        if (!it.isDirectory && !it.mkdirs()) throw PdfImportException("Not enough storage to import this document. Free some space and try again.")
    }

    fun newText(context: Context): File = File(directory(context), "${UUID.randomUUID()}.txt")

    /** Copies [input] to [destination] up to [limits.maxFileBytes] and returns the lowercase SHA-256 hex digest. */
    fun copy(input: InputStream, destination: File, limits: PdfLimits, cancelled: () -> Boolean): String {
        limits.validate()
        val digest = MessageDigest.getInstance("SHA-256")
        destination.outputStream().use { out ->
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                if (cancelled()) throw CancellationException("Document import cancelled")
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > limits.maxFileBytes) {
                    throw PdfImportException("Shared file exceeds the ${limits.maxFileMb} MB file limit. Increase the document file limit in Voice & speed, or share a smaller file.")
                }
                digest.update(buffer, 0, count)
                out.write(buffer, 0, count)
            }
        }
        return toHex(digest.digest())
    }

    /** Returns the lowercase SHA-256 hex digest of an existing [file]. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return toHex(digest.digest())
    }

    private fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    /** A private file id, never a caller-supplied path or a textbook-sized Intent extra. */
    fun read(context: Context, id: String): String {
        require(Regex("[0-9a-fA-F-]{36}\\.txt").matches(id)) { "Invalid document import. Share the file again." }
        val file = File(directory(context), id)
        if (!file.isFile) throw PdfImportException("The imported document is no longer available. Share it again.")
        val limit = limits(context).maxTextChars
        try {
            return file.bufferedReader().use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (count > limit - result.length) throw PdfImportException("Imported text exceeds the current text limit. Increase it in Voice & speed and share the file again.")
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
        } finally {
            if (!file.delete()) android.util.Log.w("Readly", "Couldn't remove consumed PDF text import")
        }
    }
}
