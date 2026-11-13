package app.readly.input.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.Writer
import java.util.concurrent.CancellationException

/** A shared photo/screenshot is one OCR page in the same durable document pipeline as PDFs. */
class SharedImagePages(
    context: Context,
    private val file: File,
    private val options: PdfOcrOptions,
    private val cancelled: () -> Boolean,
    private val onRecognizing: () -> Unit,
    private val save: (PdfPageDraft) -> Unit,
) : PdfPages {
    init { options.validate() }
    private val recognizer = OnDeviceOcr(context, options, cancelled)
    override val count = 1
    override var ocrPages = 0
        private set
    override var ocrLabel = ""
        private set

    override fun writePage(index: Int, output: Writer) {
        require(index == 0)
        checkCancelled()
        onRecognizing()
        val bitmap = decode()
        try {
            val result = recognizer.recognize(bitmap, 0, imageLayout = true)
            checkCancelled()
            ocrPages = 1
            ocrLabel = result.label
            output.write(result.text)
            save(PdfPageDraft(0, result.text, PdfPageSource.OCR, result.modelId, result.confidence))
        } finally { bitmap.recycle() }
    }

    private fun checkCancelled() {
        if (cancelled()) throw CancellationException("Image import cancelled")
    }

    private fun decode(): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw PdfImportException("This picture couldn't be decoded. Share the original image or save it as JPEG or PNG.")
        }
        if (bounds.outMimeType !in setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "image/avif")) {
            throw PdfImportException("This image format isn't supported. Share a still JPEG, PNG, WebP, HEIC or AVIF picture.")
        }
        ImageInput.rejectAnimation(file, bounds.outMimeType, cancelled)
        val orientation = ImageInput.orientation(ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
        checkCancelled()
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = ImageInput.sampleSize(bounds.outWidth, bounds.outHeight, options.maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw PdfImportException("Couldn't decode this picture. Try sharing a JPEG or PNG copy.")
        var oriented = decoded
        try {
            if (orientation.degrees != 0 || orientation.mirrorHorizontal) {
                val matrix = Matrix().apply {
                    setRotate(orientation.degrees.toFloat())
                    if (orientation.mirrorHorizontal) postScale(-1f, 1f)
                }
                oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            }
            checkCancelled()
            // Transparent screenshots are composited on white; dark/opaque images keep their own colors.
            val opaque = Bitmap.createBitmap(oriented.width, oriented.height, Bitmap.Config.ARGB_8888)
            try {
                opaque.eraseColor(Color.WHITE)
                Canvas(opaque).drawBitmap(oriented, 0f, 0f, null)
                return opaque
            } catch (error: Exception) {
                opaque.recycle()
                android.util.Log.e("Readly", "Couldn't prepare shared image pixels", error)
                throw PdfImportException("Couldn't prepare this picture for OCR. Try sharing a smaller JPEG or PNG.")
            }
        } finally {
            if (oriented !== decoded) oriented.recycle()
            decoded.recycle()
        }
    }

    override fun close() = recognizer.close()
}
