package app.readly

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import app.readly.input.ShareInput
import app.readly.input.ShareRequest

/**
 * The share sheet's "Read later": saves a shared link to the car's Saved tab without
 * reading it now. Only links can be saved; anything else says so.
 */
class ReadLaterActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val message = try {
            val request = ShareInput.resolve(
                action = intent.action,
                type = intent.type,
                text = intent.getStringExtra(Intent.EXTRA_TEXT),
                subject = intent.getStringExtra(Intent.EXTRA_SUBJECT),
                htmlText = null,
                streamName = null,
                streamMime = null,
                streamText = null,
            )
            if (request !is ShareRequest.Url) {
                throw IllegalArgumentException("Read later saves links. Share the page's link instead.")
            }
            val title = request.titleHint.orEmpty()
            (application as ReadlyApp).feeds.addSaved(request.url, title)
            "Saved for the car: ${title.ifBlank { request.url }}"
        } catch (e: IllegalArgumentException) {
            Log.w("Readly", "Read later refused a share: ${e.message}")
            e.message ?: "That couldn't be saved."
        } catch (e: java.io.IOException) {
            Log.e("Readly", "Read later couldn't save a link", e)
            e.message ?: "That couldn't be saved."
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
