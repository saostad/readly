package app.readly.input.pdf

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ImageInputTest {
    @Test fun routesGalleryAndScreenshotSharesWithMimeOrFilename() {
        assertTrue(ImageInput.isImage("image/png", null, null))
        assertTrue(ImageInput.isImage("IMAGE/JPEG; charset=binary", null, null))
        assertTrue(ImageInput.isImage("application/octet-stream", "Camera.HEIC", null))
        assertTrue(ImageInput.isImage(null, null, "image/webp"))
        assertTrue(ImageInput.isImage(null, "screenshot.PNG", null))
        assertFalse(ImageInput.isImage("application/pdf", "book.pdf", null))
        assertFalse(ImageInput.isImage(null, null, null))
        assertFalse(ImageInput.isImage("text/plain", "not-a-photo.jpg.txt", null))
    }

    @Test fun cameraAndScrollingScreenshotDecodeWithinConfiguredResolution() {
        for ((width, height) in listOf(12000 to 9000, 1080 to 48000, 4000 to 3000, 640 to 480, Int.MAX_VALUE to 1)) {
            for (maxSide in listOf(800, 2400, 4000)) {
                val sample = ImageInput.sampleSize(width, height, maxSide)
                assertTrue(sample > 0 && sample and (sample - 1) == 0)
                assertTrue((maxOf(width, height).toLong() + sample - 1) / sample <= maxSide)
                if (sample > 1) assertTrue(maxOf(width, height).toLong() > maxSide.toLong() * (sample / 2))
            }
        }
        assertEquals(1, ImageInput.sampleSize(2400, 1800, 2400))
        assertThrows(IllegalArgumentException::class.java) { ImageInput.sampleSize(0, 100, 2400) }
        assertThrows(IllegalArgumentException::class.java) { ImageInput.sampleSize(Int.MAX_VALUE, 1, 1) }
    }

    @Test fun cameraExifRotationsAndMirrorsFollowTheExifProtocol() {
        val rotations = listOf(0, 0, 0, 180, 180, 90, 90, 270, 270)
        val mirrors = setOf(2, 4, 5, 7)
        for (tag in 0..8) {
            val orientation = ImageInput.orientation(tag)
            assertEquals("EXIF $tag", rotations[tag], orientation.degrees)
            assertEquals("EXIF $tag", tag in mirrors, orientation.mirrorHorizontal)
        }
        assertThrows(PdfImportException::class.java) { ImageInput.orientation(9) }
    }

    private fun webp(animated: Boolean): ByteArray = ByteArray(32).apply {
        "RIFF".toByteArray().copyInto(this, 0)
        "WEBPVP8X".toByteArray().copyInto(this, 8)
        this[20] = if (animated) 2 else 0
    }

    @Test fun animatedWebpCannotSilentlyBecomeOneStillFrame() {
        assertTrue(ImageInput.animatedWebp(webp(true), 32))
        assertFalse(ImageInput.animatedWebp(webp(false), 32))
        assertFalse(ImageInput.animatedWebp(webp(true), 20))
        assertFalse(ImageInput.animatedWebp(ByteArray(1), 32))
        assertFalse(ImageInput.animatedWebp(ByteArray(32), 32))
        withFile(webp(true)) { file ->
            val error = assertThrows(PdfImportException::class.java) { ImageInput.rejectAnimation(file, "image/webp") { false } }
            assertTrue(error.message!!.contains("still photo"))
        }
        withFile(webp(false)) { file -> ImageInput.rejectAnimation(file, "image/webp") { false } }
    }

    private fun png(vararg chunks: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            for ((type, data) in chunks) {
                output.writeInt(data.size)
                output.write(type.toByteArray(Charsets.US_ASCII))
                output.write(data)
                output.writeInt(0) // CRC is validated by Android's decoder, not the animation guard.
            }
        }
    }.toByteArray()

    @Test fun pngAnimationAndTruncatedChunksAreErrorsAndChecksCanBeCancelled() {
        val header = "IHDR" to ByteArray(13)
        withFile(png(header, "acTL" to ByteArray(8), "IDAT" to ByteArray(2))) { file ->
            assertThrows(PdfImportException::class.java) { ImageInput.rejectAnimation(file, "image/png") { false } }
        }
        withFile(png(header, "IDAT" to ByteArray(2))) { file ->
            ImageInput.rejectAnimation(file, "image/png") { false }
            assertThrows(CancellationException::class.java) { ImageInput.rejectAnimation(file, "image/png") { true } }
        }
        withFile(png(header, "IDAT" to ByteArray(16)).dropLast(12).toByteArray()) { file ->
            val error = assertThrows(PdfImportException::class.java) { ImageInput.rejectAnimation(file, "image/png") { false } }
            assertTrue(error.message!!.contains("incomplete"))
        }
    }

    @Test fun imageLayoutIsValidatedAndExplicitlyConfigurable() {
        assertEquals("auto", PdfOcrOptions().imageLayout)
        assertEquals(3, PdfOcrOptions().imagePageSegMode)
        for ((layout, protocolValue) in mapOf("sparse" to 11, "auto" to 3, "block" to 6, "line" to 7)) {
            val options = PdfOcrOptions(imageLayout = layout)
            options.validate()
            assertEquals(protocolValue, options.imagePageSegMode)
        }
        assertThrows(IllegalArgumentException::class.java) { PdfOcrOptions(imageLayout = "unknown").validate() }
    }

    private fun withFile(bytes: ByteArray, block: (java.io.File) -> Unit) {
        val file = Files.createTempFile("readly-image-test-", ".bin")
        try {
            Files.write(file, bytes)
            block(file.toFile())
        } finally { Files.delete(file) }
    }
}
