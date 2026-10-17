package app.readly.input.sites

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream
import org.json.JSONException
import org.json.JSONObject

/**
 * Shared bounded reader for the official Bypass Paywalls Clean update ZIP.
 *
 * Extracted from the single-site NYT parser so every importer enforces the
 * same caps: the archive is read with the JDK ZIP API as data, entry
 * contents are streamed with manual byte counting (declared sizes are
 * never trusted), duplicate or missing required entries are rejected, and
 * nothing is ever extracted to disk. Only `sites.js` and its sibling
 * `manifest.json` are required; every other entry is drained within the
 * shared budget and ignored. An optional `LICENSE` next to `sites.js` can
 * be captured for shipped-data attribution.
 *
 * Every function takes the caller's [label] (`"NYT rule update"`,
 * `"Site rule update"`) so error messages name the operation exactly as
 * each importer's tests expect.
 */
internal object UpdateArchive {
    /** Largest accepted update download (the inspected ZIP is ~0.3 MiB). */
    const val MAX_COMPRESSED_BYTES = 8L * 1024 * 1024

    /** Largest total uncompressed bytes scanned across all archive entries. */
    const val MAX_TOTAL_UNCOMPRESSED_BYTES = 32L * 1024 * 1024

    /** Largest accepted `sites.js` (the inspected one is ~0.1 MiB). */
    const val MAX_SITES_JS_BYTES = 2L * 1024 * 1024

    /** Largest accepted `manifest.json` (the inspected one is ~31 KiB). */
    const val MAX_MANIFEST_BYTES = 64L * 1024

    /** Largest accepted `LICENSE` attribution text. */
    const val MAX_LICENSE_BYTES = 64L * 1024

    /** Largest accepted number of archive entries (the inspected ZIP has 65). */
    const val MAX_ENTRIES = 256

    private enum class EntryKind { NONE, SITES, MANIFEST, LICENSE }

    /** Required decoded texts plus the optional LICENSE attribution text. */
    data class ArchiveTexts(
        val sitesJs: String,
        val manifestJson: String,
        /** Null when the archive ships no LICENSE next to `sites.js`. */
        val license: String?,
    )

    @Throws(JsDataException::class)
    fun readArchive(zipBytes: ByteArray, label: String): ArchiveTexts {
        if (zipBytes.size > MAX_COMPRESSED_BYTES) {
            throw JsDataException(
                "$label is too large: ${zipBytes.size} compressed bytes (over 8 MiB)",
            )
        }
        var sitesName: String? = null
        var sitesData: ByteArray? = null
        val manifests = mutableListOf<NamedBlob>()
        val licenses = mutableListOf<NamedBlob>()
        var entries = 0
        var totalUncompressed = 0L
        try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
                while (true) {
                    val entry = try {
                        zip.nextEntry ?: break
                    } catch (e: Exception) {
                        throw JsDataException(
                            "$label ZIP is unreadable: " +
                                "${e.javaClass.name}: ${e.message}",
                            e,
                        )
                    }
                    entries++
                    if (entries > MAX_ENTRIES) {
                        throw JsDataException(
                            "$label ZIP has too many entries (over $MAX_ENTRIES)",
                        )
                    }
                    val name = entry.name ?: ""
                    val kind = classifyEntry(name)
                    val fileCap = when (kind) {
                        EntryKind.SITES -> MAX_SITES_JS_BYTES
                        EntryKind.MANIFEST -> MAX_MANIFEST_BYTES
                        EntryKind.LICENSE -> MAX_LICENSE_BYTES
                        EntryKind.NONE -> Long.MAX_VALUE
                    }
                    // Every entry is consumed through the shared budget so a
                    // flood of small files cannot dodge the total cap.
                    val budget = EntryBudget(totalUncompressed)
                    val data = readEntryBounded(zip, name, fileCap, kind, budget, label)
                    totalUncompressed = budget.total
                    when (kind) {
                        EntryKind.SITES -> {
                            if (sitesData != null) {
                                throw JsDataException(
                                    "$label ZIP has duplicate \"sites.js\" entries",
                                )
                            }
                            sitesName = name
                            sitesData = data
                        }
                        EntryKind.MANIFEST -> {
                            // Hold all manifests; the one next to sites.js is
                            // picked below (the archive also ships copies
                            // under custom/).
                            manifests.add(NamedBlob(name, data))
                        }
                        EntryKind.LICENSE -> licenses.add(NamedBlob(name, data))
                        EntryKind.NONE -> Unit
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: JsDataException) {
            throw e
        } catch (e: Exception) {
            throw JsDataException(
                "$label ZIP is unreadable: ${e.javaClass.name}: ${e.message}",
                e,
            )
        }
        if (entries == 0 && !looksLikeZip(zipBytes)) {
            throw JsDataException("$label is not a ZIP archive")
        }
        val sites = sitesData ?: throw JsDataException(
            "$label ZIP has no \"sites.js\" entry",
        )
        val dir = sitesName!!.substringBeforeLast('/', "")
        val manifest = selectSingle(manifests, dir, "manifest.json", label, required = true)!!
        val license = selectLicense(licenses, dir, label)
        return ArchiveTexts(
            sitesJs = decodeUtf8(sites, "$label \"sites.js\""),
            manifestJson = decodeUtf8(manifest, "$label \"manifest.json\""),
            license = license?.let { decodeUtf8(it, "$label \"LICENSE\"") },
        )
    }

    private class EntryBudget(var total: Long)

    private data class NamedBlob(val name: String, val data: ByteArray)

    private fun selectSingle(
        candidates: List<NamedBlob>,
        dir: String,
        file: String,
        label: String,
        required: Boolean,
    ): ByteArray? {
        val mine = candidates.filter { it.name.substringBeforeLast('/', "") == dir }
        if (mine.isEmpty()) {
            if (required) {
                throw JsDataException(
                    "$label ZIP has no \"$file\" next to \"sites.js\"",
                )
            }
            return null
        }
        if (mine.size > 1) {
            throw JsDataException(
                "$label ZIP has duplicate \"$file\" entries next to \"sites.js\"",
            )
        }
        return mine.single().data
    }

    /**
     * Optional LICENSE next to `sites.js`. The official archive ships both
     * `LICENSE` and `LICENSE.txt` with identical text; prefer the exact
     * name, else the `.txt` twin. True duplicates of one name are rejected.
     */
    private fun selectLicense(candidates: List<NamedBlob>, dir: String, label: String): ByteArray? {
        val mine = candidates.filter { it.name.substringBeforeLast('/', "") == dir }
        if (mine.isEmpty()) return null
        val exact = mine.filter { it.name.substringAfterLast('/') == "LICENSE" }
        val pool = exact.ifEmpty { mine.filter { it.name.substringAfterLast('/') == "LICENSE.txt" } }
        if (pool.isEmpty()) return null
        if (pool.size > 1) {
            throw JsDataException(
                "$label ZIP has duplicate \"LICENSE\" entries next to \"sites.js\"",
            )
        }
        return pool.single().data
    }

    private fun looksLikeZip(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val sig = (bytes[0].toInt() and 0xFF) or
            ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16) or
            ((bytes[3].toInt() and 0xFF) shl 24)
        // Local-file, end-of-central-directory (empty archive), span marker.
        return sig == 0x04034b50 || sig == 0x06054b50 || sig == 0x07064b50
    }

    /**
     * Names only the required entries; traversal-named (`..`, absolute) and
     * directory entries are never required and never touch disk.
     */
    private fun classifyEntry(name: String): EntryKind {
        if (name.isEmpty() || name.startsWith("/")) return EntryKind.NONE
        val segments = name.split('/')
        if (".." in segments) return EntryKind.NONE
        return when (segments.last()) {
            "sites.js" -> EntryKind.SITES
            "manifest.json" -> EntryKind.MANIFEST
            "LICENSE", "LICENSE.txt" -> EntryKind.LICENSE
            else -> EntryKind.NONE
        }
    }

    private fun readEntryBounded(
        zip: ZipInputStream,
        name: String,
        fileCap: Long,
        kind: EntryKind,
        budget: EntryBudget,
        label: String,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var fileTotal = 0L
        while (true) {
            val n = try {
                zip.read(buf)
            } catch (e: Exception) {
                throw JsDataException(
                    "$label ZIP entry \"$name\" is unreadable: " +
                        "${e.javaClass.name}: ${e.message}",
                    e,
                )
            }
            if (n < 0) break
            fileTotal += n
            if (fileTotal > fileCap) {
                val what = when (kind) {
                    EntryKind.SITES -> "\"sites.js\" (over 2 MiB)"
                    EntryKind.MANIFEST -> "\"manifest.json\" (over 64 KiB)"
                    EntryKind.LICENSE -> "\"LICENSE\" (over 64 KiB)"
                    EntryKind.NONE -> "\"$name\""
                }
                throw JsDataException("$label $what is too large")
            }
            budget.total += n
            if (budget.total > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                throw JsDataException(
                    "$label uncompressed data is too large (over 32 MiB)",
                )
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun decodeUtf8(bytes: ByteArray, what: String): String {
        return try {
            Charset.forName("UTF-8").newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw JsDataException(
                "$what is not valid UTF-8: ${e.javaClass.name}: ${e.message}",
                e,
            )
        }
    }

    /**
     * Strict `version` from `manifest.json`: not valid JSON is malformed,
     * missing/invalid version is valid-but-unsupported.
     */
    fun parseManifestVersion(manifestJson: String, label: String): String {
        val o = try {
            JSONObject(manifestJson)
        } catch (e: JSONException) {
            throw JsDataException(
                "$label \"manifest.json\" is not valid JSON: " +
                    "${e.javaClass.name}: ${e.message}",
                e,
            )
        }
        if (!o.has("version")) {
            throw JsDataUnsupportedException(
                "$label \"manifest.json\" has no \"version\"",
            )
        }
        val v = o.opt("version")
        if (v !is String || !SiteVersions.isValidVersion(v)) {
            throw JsDataUnsupportedException(
                "$label \"manifest.json\" has invalid version " +
                    "${describeJsonValue(v)}: " +
                    "expected a numeric dotted version like \"4.4.7.4\"",
            )
        }
        return v
    }

    /** Short single-line rendering of a JSON value for error messages. */
    fun describeJsonValue(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "missing"
        is String -> "\"${v.take(64)}\""
        else -> "${v.javaClass.simpleName}(${"$v".take(64)})"
    }
}
