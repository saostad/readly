package app.readly.input.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.Writer

class PdfBoxPages(context: Context, file: File) : PdfPages {
    private val document: PDDocument
    private val stripper by lazy { PDFTextStripper().apply { sortByPosition = true } }

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
        document = try {
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir))
        } catch (e: InvalidPasswordException) {
            android.util.Log.e("Readly", "PDF import refused a password-protected document", e)
            throw PdfImportException("This PDF is password-protected. Unlock it in your PDF app, then share an unlocked copy.")
        }
        if (!document.currentAccessPermission.canExtractContent()) {
            document.close()
            throw PdfImportException("This PDF does not allow text extraction. Share a copy that permits text extraction.")
        }
    }

    override val count: Int get() = document.numberOfPages
    override fun writePage(index: Int, output: Writer) {
        stripper.startPage = index + 1
        stripper.endPage = index + 1
        stripper.writeText(document, output)
    }
    override fun close() = document.close()
}
