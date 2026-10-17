package app.readly.input.sites

import app.readly.input.FetchConnection
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI

/**
 * Replaces [dest] with [tmp]. Separated for deterministic failure tests:
 * production uses [DefaultFileMover], tests inject a fake that throws.
 */
fun interface FileMover {
    @Throws(IOException::class)
    fun move(tmp: File, dest: File)
}

/**
 * Atomic replace via NIO (minSdk 26). When the filesystem does not support
 * an atomic move this fails with the concrete error and keeps the old file;
 * there is deliberately no non-atomic fallback, whose crash behavior between
 * truncate and rewrite is not guaranteed.
 */
object DefaultFileMover : FileMover {
    override fun move(tmp: File, dest: File) {
        try {
            java.nio.file.Files.move(
                tmp.toPath(),
                dest.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            throw IOException(
                "Atomic replace is not supported on this storage: " +
                    "${e.javaClass.name}: ${e.message}",
                e,
            )
        }
    }
}

/**
 * Deletes a sibling `.tmp` file after a failed write. Separated so tests can
 * inject a failing deleter; production uses [DefaultFileDeleter].
 * Returns true when the temp file is gone (or was never there).
 */
fun interface FileDeleter {
    fun delete(file: File): Boolean
}

/** Production [FileDeleter] backed by [File.delete]. */
object DefaultFileDeleter : FileDeleter {
    override fun delete(file: File): Boolean {
        if (!file.exists()) return true
        return file.delete()
    }
}

/** Largest cached rule/status JSON accepted from app-private storage. */
const val MAX_CACHE_JSON_BYTES = 8L * 1024 * 1024

/**
 * File-backed [JsonRuleStorage]. Writes go to a sibling `.tmp` file
 * followed by an atomic replace, so a crash mid-write never leaves a
 * half-written rule behind; readers always see the previous complete file
 * or the new complete file. On replacement failure the old file is kept
 * byte-for-byte and only the temp file is cleaned.
 *
 * Reads are bounded to [MAX_CACHE_JSON_BYTES]: an overgrown cache file
 * reports a read failure (the store falls back to its bundled data with a
 * notice) instead of exhausting memory. Writes enforce the same bound on
 * the encoded UTF-8 bytes before touching disk, so an update can never
 * publish a cache the next startup cannot read back.
 *
 * @param label short owner noun for errors (`"NYT rule"`, `"Site rule"`).
 */
class AtomicJsonFileStore(
    private val dir: File,
    private val policyName: String,
    private val statusName: String,
    private val label: String,
    private val mover: FileMover = DefaultFileMover,
    private val deleter: FileDeleter = DefaultFileDeleter,
    private val maxReadBytes: Long = MAX_CACHE_JSON_BYTES,
) : JsonRuleStorage {
    override fun readPolicyJson(): String? = readIfExists(File(dir, policyName))
    override fun writePolicyJson(json: String) = atomicWrite(File(dir, policyName), json)
    override fun readStatusJson(): String? = readIfExists(File(dir, statusName))
    override fun writeStatusJson(json: String) = atomicWrite(File(dir, statusName), json)

    private fun readIfExists(file: File): String? {
        if (!file.exists()) return null
        val length = file.length()
        if (length > maxReadBytes) {
            throw IOException(
                "$label cache read failed: \"${file.name}\" is too large " +
                    "($length bytes, over $maxReadBytes bytes)",
            )
        }
        return file.readText(Charsets.UTF_8)
    }

    private fun atomicWrite(file: File, text: String) {
        try {
            val encodedBytes = text.toByteArray(Charsets.UTF_8).size
            if (encodedBytes > maxReadBytes) {
                throw IOException(
                    "$label cache write failed: \"${file.name}\" is too large " +
                        "($encodedBytes bytes, over $maxReadBytes bytes)",
                )
            }
            dir.mkdirs()
            val tmp = File(dir, file.name + ".tmp")
            try {
                tmp.writeText(text, Charsets.UTF_8)
            } catch (e: Exception) {
                val cleanup = deleteTmpReporting(tmp)
                val base = "$label cache write failed: ${e.javaClass.name}: ${e.message}"
                throw IOException(withCleanup(base, cleanup), e)
            }
            try {
                mover.move(tmp, file)
            } catch (e: IOException) {
                val cleanup = deleteTmpReporting(tmp)
                val msg = e.message ?: "$label cache write failed: ${e.javaClass.name}"
                if (cleanup == null) throw e
                throw IOException(withCleanup(msg, cleanup), e)
            } catch (e: Exception) {
                val cleanup = deleteTmpReporting(tmp)
                val base = "$label cache write failed: ${e.javaClass.name}: ${e.message}"
                throw IOException(withCleanup(base, cleanup), e)
            }
        } catch (e: IOException) {
            // Preserve our own detailed errors; bare failures gain the operation.
            val msg = e.message
            if (msg != null && msg.startsWith(label)) throw e
            throw IOException(
                "$label cache write failed: ${e.javaClass.name}: ${e.message}",
                e,
            )
        } catch (e: Exception) {
            throw IOException(
                "$label cache write failed: ${e.javaClass.name}: ${e.message}",
                e,
            )
        }
    }

    /**
     * Removes [tmp] after a failed write. Never throws and never swallows the
     * primary failure: returns null on success, otherwise a fragment the
     * caller appends to the primary error message.
     */
    private fun deleteTmpReporting(tmp: File): String? {
        return try {
            if (deleter.delete(tmp)) {
                null
            } else {
                "temp cleanup of \"${tmp.name}\" failed (delete returned false)"
            }
        } catch (e: Exception) {
            "temp cleanup of \"${tmp.name}\" failed: ${e.javaClass.name}: ${e.message}"
        }
    }

    private fun withCleanup(primary: String, cleanup: String?): String =
        if (cleanup == null) primary else "$primary ($cleanup)"
}

/**
 * Bounded fixed-origin HTTPS downloader over [FetchConnection]. The start URL
 * must be exactly [expectedUrl], and every redirect is validated: HTTPS only
 * (no downgrade), same host (no off-origin), no credentials, no odd ports.
 * Bodies are bounded to [maxBytes].
 *
 * @param label operation noun for errors (`"NYT rule update"`,
 *   `"Site rule update"`).
 */
class FixedOriginDownloader(
    private val opener: (String) -> FetchConnection,
    private val expectedUrl: String,
    private val label: String,
    private val maxBytes: Long = UpdateArchive.MAX_COMPRESSED_BYTES,
) {
    companion object {
        const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }

    @Throws(IOException::class)
    fun download(url: String): ByteArray {
        if (url != expectedUrl) {
            throw IOException("$label refused unexpected URL: $url")
        }
        val startHost = try {
            URI(url).host ?: throw IOException("$label failed: official URL has no host")
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(
                "$label failed: official URL is invalid: " +
                    "${e.javaClass.name}: ${e.message}",
                e,
            )
        }
        var current = url
        for (hop in 0..MAX_REDIRECTS) {
            var redirectTo: String? = null
            opener(current).use { conn ->
                val code = conn.statusCode
                if (code in REDIRECT_CODES) {
                    val location = conn.header("Location")
                    if (location.isNullOrBlank()) {
                        throw IOException(
                            "$label failed: HTTP $code redirect with no Location header",
                        )
                    }
                    redirectTo = resolveRedirect(current, location, startHost)
                } else {
                    if (code !in 200..299) {
                        val detail = if (conn.statusMessage.isBlank()) "" else " ${conn.statusMessage}"
                        throw IOException("$label failed: HTTP $code$detail")
                    }
                    return readBounded(conn.input())
                }
            }
            current = redirectTo ?: throw IOException(
                "$label failed: redirect was not followed",
            )
        }
        throw IOException("$label failed: too many redirects (over $MAX_REDIRECTS)")
    }

    private fun resolveRedirect(current: String, location: String, startHost: String): String {
        val resolved = try {
            URI(current).resolve(location)
        } catch (e: Exception) {
            throw IOException(
                "$label failed: bad redirect Location \"${location.take(120)}\": " +
                    "${e.javaClass.name}: ${e.message}",
                e,
            )
        }
        if (!resolved.scheme.equals("https", ignoreCase = true)) {
            throw IOException(
                "$label failed: redirect to non-HTTPS URL " +
                    "\"${(resolved.scheme ?: "?").take(16)}://${(resolved.host ?: "?").take(120)}\" " +
                    "from \"${location.take(120)}\"",
            )
        }
        val host = resolved.host ?: throw IOException(
            "$label failed: redirect has no host: \"${location.take(120)}\"",
        )
        if (!host.equals(startHost, ignoreCase = true)) {
            throw IOException(
                "$label failed: redirect off update host \"$startHost\" to \"$host\"",
            )
        }
        if (resolved.userInfo != null) {
            throw IOException("$label failed: redirect carries credentials")
        }
        val port = resolved.port
        if (port != -1 && port != 443) {
            throw IOException("$label failed: redirect to odd port $port")
        }
        return resolved.toString()
    }

    private fun readBounded(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var total = 0L
        input.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) {
                    throw IOException("$label failed: download is too large (over ${maxBytes / 1024 / 1024} MiB)")
                }
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }
}
