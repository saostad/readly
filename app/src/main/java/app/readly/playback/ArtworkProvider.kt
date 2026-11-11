package app.readly.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Serves the website tiles in Android Auto's grid as PNGs (`content://app.readly.artwork/site/<name>`),
 * so the list sends small addresses instead of a bitmap per site over the binder.
 * Read-only, and it only ever draws a name on Readly's colors.
 */
class ArtworkProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "app.readly.artwork"
        private const val MAX_NAME_CHARS = 80
        private const val TAG = "Readly"

        fun siteUri(name: String): Uri =
            Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("site")
                .appendPath(name.take(MAX_NAME_CHARS)).build()
    }

    private val artwork = SiteArtwork()

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Artwork is read-only")
        val segments = uri.pathSegments
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
