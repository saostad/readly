package app.readly.input.pdf

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException

/** Image routing and Android's EXIF/sample-size protocol, kept independent of bitmap allocation. */
object ImageInput {
    fun isImage(type: String?, name: String?, mime: String?): Boolean =
        listOf(type, mime).any { it?.substringBefore(';')?.trim()?.startsWith("image/", true) == true } ||
            name?.substringAfterLast('.', "")?.lowercase() in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "gif", "bmp")

    fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        require(width > 0 && height > 0 && maxSide > 0) { "Image dimensions must be positive" }
        var sample = 1L
        // BitmapFactory's power-of-two sampling keeps even large camera images bounded before decoding.
        while (maxOf(width, height).toLong() > maxSide.toLong() * sample) sample *= 2
        require(sample <= Int.MAX_VALUE) { "Image dimensions exceed the decoder's sampling range" }
        return sample.toInt()
    }

    data class Orientation(val degrees: Int, val mirrorHorizontal: Boolean)
    fun orientation(exif: Int): Orientation = when (exif) {
        0, 1 -> Orientation(0, false)
        2 -> Orientation(0, true)
        3 -> Orientation(180, false)
        4 -> Orientation(180, true)
        5 -> Orientation(90, true)
        6 -> Orientation(90, false)
        7 -> Orientation(270, true)
        8 -> Orientation(270, false)
        else -> throw PdfImportException("This picture has invalid rotation information. Save it as a new image and share that copy.")
    }

    fun animatedWebp(header: ByteArray, size: Int): Boolean = minOf(size, header.size) >= 21 &&
        String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(header, 8, 4, Charsets.US_ASCII) == "WEBP" &&
        String(header, 12, 4, Charsets.US_ASCII) == "VP8X" && (header[20].toInt() and 2) != 0

    fun rejectAnimation(file: File, mime: String?, cancelled: () -> Boolean) {
        RandomAccessFile(file, "r").use { input ->
            val header = ByteArray(32)
            val count = input.read(header)
            if (animatedWebp(header, count)) {
                throw PdfImportException("Animated pictures aren't supported. Share a still photo or screenshot instead.")
            }
            if (mime != "image/png") return
            // APNG declares animation before its first image-data chunk. Never silently read one frame.
            input.seek(8)
            while (input.filePointer <= input.length() - 12) {
                if (cancelled()) throw CancellationException("Image import cancelled")
                val length = input.readInt().toLong() and 0xffffffffL
                val type = ByteArray(4).also { input.readFully(it) }.toString(Charsets.US_ASCII)
                if (type == "acTL") throw PdfImportException("Animated pictures aren't supported. Share a still photo or screenshot instead.")
                val next = input.filePointer + length + 4
                if (next > input.length()) throw PdfImportException("This PNG picture is incomplete. Share the original image again.")
                if (type == "IDAT") return
                input.seek(next)
            }
        }
    }
}
