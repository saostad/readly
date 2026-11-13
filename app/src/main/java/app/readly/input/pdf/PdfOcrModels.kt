package app.readly.input.pdf

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Pinned, Apache-2.0 English/Persian models ship in the APK; documents are never uploaded. */
object PdfOcrModels {
    private val hashes = mapOf(
        "eng" to "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2",
        "fas" to "db1c0a91208aff00d3cf1ed2c1d23f76419afd5f024688b4f71adc3f2ce4a505",
    )
    @Synchronized fun prepare(context: Context, languages: String, cancelled: () -> Boolean): File {
        val base = File(context.filesDir, "pdf-ocr")
        val data = File(base, "tessdata")
        if (!data.isDirectory && !data.mkdirs()) throw PdfImportException("Not enough storage for PDF OCR. Free some space and try again.")
        for (language in languages.split('+')) {
            val expected = hashes[language] ?: throw PdfImportException("Unsupported PDF OCR language. Check PDF settings.")
            val temporary = File.createTempFile(language, ".part", data)
            try {
                val hash = MessageDigest.getInstance("SHA-256")
                context.assets.open("ocr/$language.traineddata").use { input ->
                    temporary.outputStream().use { out ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            if (cancelled()) throw CancellationException("PDF import cancelled")
                            val count = input.read(buffer)
                            if (count < 0) break
                            hash.update(buffer, 0, count); out.write(buffer, 0, count)
                        }
                    }
                }
                if (hash.digest().joinToString("") { "%02x".format(it) } != expected) {
                    throw PdfImportException("Readly's OCR data failed its integrity check. Reinstall Readly from the official release.")
                }
                Files.move(temporary.toPath(), File(data, "$language.traineddata").toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                if (temporary.exists() && !temporary.delete()) android.util.Log.w("Readly", "Couldn't remove temporary OCR model file")
            }
        }
        return base
    }
}
