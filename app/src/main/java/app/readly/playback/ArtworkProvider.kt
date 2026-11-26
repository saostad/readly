package app.readly.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest

/**
 * Serves the website tiles in Android Auto's grid as PNGs (`content://app.readly.artwork/site/<name>`),
 * so the list sends small addresses instead of a bitmap per site over the binder.
 * Serves cached publisher images and generated artwork for websites without an image.
 */
class ArtworkProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "app.readly.artwork"
        private const val MAX_NAME_CHARS = 80
        private const val TAG = "Readly"

        fun publisherUri(key: String): Uri = Uri.Builder().scheme("content").authority(AUTHORITY)
            .appendPath("publisher").appendPath(key).build()

        fun publisherFile(context: Context, key: String): File {
            require(key.matches(Regex("[0-9a-f]{64}"))) { "Invalid publisher image key" }
            return File(context.cacheDir, "artwork/$key.png")
        }

        fun cachePublisher(context: Context, bytes: ByteArray): String {
            val key = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val file = publisherFile(context, key)
            if (file.isFile) return key
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("Unsupported publisher artwork")
            options.inJustDecodeBounds = false
            options.inSampleSize = 1
            while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > SiteArtwork.SIZE_PX) {
                options.inSampleSize *= 2
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: throw IOException("Unreadable publisher artwork")
            file.parentFile!!.mkdirs()
            val temp = File.createTempFile("publisher-", ".png", file.parentFile)
            try {
                temp.outputStream().use {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("Image encoding failed")
                }
                if (!temp.renameTo(file)) throw IOException("Image save failed")
            } finally {
                bitmap.recycle()
                temp.delete()
            }
            return key
        }

        fun siteUri(name: String): Uri =
            Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("site")
                .appendPath(name.take(MAX_NAME_CHARS)).build()
    }

    private val artwork = SiteArtwork()

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Artwork is read-only")
        val segments = uri.pathSegments
        if (segments.size == 2 && segments[0] == "publisher") {
            return ParcelFileDescriptor.open(publisherFile(context!!, segments[1]), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val name = segments.getOrNull(1)?.take(MAX_NAME_CHARS)
        if (segments.size != 2 || segments[0] != "site" || name.isNullOrBlank()) {
            throw FileNotFoundException("No artwork at $uri")
        }
        val dir = File(context!!.cacheDir, "artwork").apply { mkdirs() }
        val file = File(dir, sha1(name) + ".png")
        if (!file.exists()) {
            try {
                val bitmap = artwork.forName(name) ?: throw FileNotFoundException("No artwork for a blank name")
                val tmp = File(dir, file.name + ".tmp")
                tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (!tmp.renameTo(file)) throw FileNotFoundException("Couldn't save artwork")
            } catch (e: Exception) {
                // The car shows the tile without a picture; the site name stays under it.
                Log.e(TAG, "Rendering Android Auto artwork failed", e)
                throw FileNotFoundException("Couldn't draw artwork: ${e.javaClass.simpleName}")
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/png"

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<String>?): Int = 0
}
