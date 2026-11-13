package app.readly

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import app.readly.input.ShareInput
import app.readly.input.ShareRequest
import app.readly.input.pdf.PdfText
import app.readly.input.pdf.ImageInput
import app.readly.model.VoiceModelStore
import app.readly.playback.ReaderService
import java.io.File

/**
 * Invisible share target: resolves the incoming share, hands it to [ReaderService]
 * and finishes at once so the share sheet closes instantly.
 *
 * Any shared file is read here in [onCreate], while the sender's URI permission is
 * still valid. Shared links open in the automatic article engine
 * ([ArticleActivity]) while the sender is still foreground, in parallel with
 * the voice load kicked off by [ReaderService.preload]. Text, HTML, Markdown
 * and file shares are read directly by [ReaderService.read] with no preload:
 * preloading the main voice before detection made a Persian share load the
 * English voice first, then the detected one.
 */
class ShareActivity : Activity() {

    companion object {
        /** A file picked inside Readly uses exactly the same path as a shared file. */
        fun fileIntent(context: Context, uri: Uri): Intent = Intent(context, ShareActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setData(uri)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri(context.getString(R.string.open_file_button), uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShare()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare()
    }

    private fun handleShare() {
        // Setup shows only when nothing is installed: a Persian-only setup
        // (Pocket, no main voice) reads shares without a detour.
        if (!VoiceModelStore.anyInstalled(File(noBackupFilesDir, "voices"))) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_NEED_VOICE, true),
            )
            finish()
            return
        }
        try {
            @Suppress("DEPRECATION")
            val sharedUri: Uri? = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                ?: intent.data?.takeIf { intent.action == Intent.ACTION_SEND }
            if (sharedUri != null) {
                val name = queryDisplayName(sharedUri)
                val mime = contentResolver.getType(sharedUri)
                val pdf = PdfText.isPdf(intent.type, name, mime)
                val image = ImageInput.isImage(intent.type, name, mime)
                if (pdf || image) {
                    Log.i("Readly", "Share: importing ${if (pdf) "PDF" else "image"}")
                    startActivity(Intent(this, DocumentImportActivity::class.java)
                        .putExtra("sharedImage", !pdf && image)
                        .setData(sharedUri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .putExtra(Intent.EXTRA_TITLE, name))
                    finish()
                    return
                }
            }
            when (val request = resolveIntent(intent)) {
                is ShareRequest.Read -> {
                    Log.i("Readly", "Share: text read; skipping main-voice preload so the detected voice loads first")
                    ReaderService.read(this, request.text, request.title, request.format)
                }
                is ShareRequest.Url -> {
                    ReaderService.preload(this)
                    startActivity(ArticleActivity.intent(this, request.url))
                }
            }
        } catch (e: IllegalArgumentException) {
            // ShareInput's own user messages (and the shared-file message) are
            // already readable; show them as they are.
            val message = e.message ?: e.javaClass.name
            Log.e("Readly", "Couldn't handle the shared item: $message", e)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            val message = "Couldn't open the shared item. Share it again from the original app."
            Log.e("Readly", message, e)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun resolveIntent(intent: Intent?): ShareRequest {
        val action = intent?.action
        val text = if (action == Intent.ACTION_PROCESS_TEXT) {
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        } else {
            intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        }
        val stream = intent?.let(::readStream)
        return ShareInput.resolve(
            action = action,
            type = intent?.type,
            text = text,
            subject = intent?.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString(),
            htmlText = intent?.getCharSequenceExtra(Intent.EXTRA_HTML_TEXT)?.toString(),
            streamName = stream?.name,
            streamMime = stream?.mime,
            streamText = stream?.text,
            streamSizeBytes = stream?.sizeBytes,
        )
    }

    private data class StreamContent(
        val name: String?,
        val mime: String?,
        val text: String,
        val sizeBytes: Long,
    )

    /**
     * Reads `EXTRA_STREAM` via the `ContentResolver`, capping the read just over the
     * limit so [ShareInput] can report the exact oversize message. `null` when the
     * share carries no stream.
     */
    private fun readStream(intent: Intent): StreamContent? {
        @Suppress("DEPRECATION")
        val uri: Uri = intent.getParcelableExtra(Intent.EXTRA_STREAM) ?: return null
        val name = queryDisplayName(uri)
        val mime = contentResolver.getType(uri)
        // Read one byte past the limit: that single byte is what proves oversize.
        val limit = ShareInput.MAX_STREAM_BYTES + 1
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            var total = 0L
            while (total < limit) {
                val room = (limit - total).coerceAtMost(buf.size.toLong()).toInt()
                val n = input.read(buf, 0, room)
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
            }
            out.toByteArray() to total
        } ?: throw IllegalArgumentException(
            "Couldn't open the shared file${if (name != null) " ($name)" else ""}.",
        )
        if (bytes.second > ShareInput.MAX_STREAM_BYTES) {
            return StreamContent(name, mime, "", bytes.second)
        }
        val text = String(bytes.first, Charsets.UTF_8).removePrefix("\uFEFF")
        return StreamContent(name, mime, text, bytes.second)
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }
}
