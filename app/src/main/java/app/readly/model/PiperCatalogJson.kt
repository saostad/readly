package app.readly.model

import java.io.IOException
import org.json.JSONArray

/** One row of `assets/piper_voices.json` (see `scripts/gen_piper_catalog.py`). */
data class PiperVoiceEntry(
    val id: String,
    val archive: String,
    val sizeBytes: Long,
    val sha256: String,
    val topDir: String,
    val modelFile: String,
    val lang: String,
    val voice: String,
    val quality: String,
    val compact: Boolean,
)

/** Parses the generated Piper voice list. Pure Kotlin; `org.json` ships on Android. */
object PiperCatalogJson {
    const val ASSET_NAME = "piper_voices.json"

    fun parse(text: String): List<PiperVoiceEntry> {
        val array = try {
            JSONArray(text)
        } catch (e: Exception) {
            throw IOException("Piper voice list is not valid JSON: ${e.message}", e)
        }
        return (0 until array.length()).map { i ->
            try {
                val o = array.getJSONObject(i)
                PiperVoiceEntry(
                    id = o.getString("id"),
                    archive = o.getString("archive"),
                    sizeBytes = o.getLong("size"),
                    sha256 = o.getString("sha256"),
                    topDir = o.getString("topDir"),
                    modelFile = o.getString("modelFile"),
                    lang = o.getString("lang"),
                    voice = o.getString("voice"),
                    quality = o.getString("quality"),
                    compact = o.getBoolean("compact"),
                )
            } catch (e: Exception) {
                throw IOException("Piper voice list entry $i is malformed: ${e.message}", e)
            }
        }
    }
}
