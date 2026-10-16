package app.readly.model

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** Stages of [VoiceModelStore.install], reported through its progress callback. */
enum class InstallStage {
    DOWNLOADING,
    VERIFYING,
}

/**
 * Minimal HTTP response. The store talks to this instead of [HttpURLConnection] directly so
 * unit tests can serve fixture archives without a network.
 */
interface DownloadConnection : Closeable {
    val statusCode: Int
    val statusMessage: String
    fun input(): InputStream
}

/**
 * Downloads, verifies and unpacks the Kokoro voice model.
 *
 * Plain JVM Kotlin with no Android types, so it is unit-testable on the host JVM.
 *
 * @param baseDir directory holding the download and the installed model
 *   (on device: `context.noBackupFilesDir/voices`).
 * @param urlOpener opens [ARCHIVE_URL]; production passes [httpUrlOpener].
 * @param expectedSha256 expected SHA-256 of the archive; a parameter so tests can inject
 *   fixture hashes (production uses [ARCHIVE_SHA256]).
 * @param expectedSizeBytes expected archive size in bytes; a parameter so tests can inject
 *   fixture sizes (production uses [ARCHIVE_SIZE_BYTES]).
 * @param onInfo receives messages that production logs with `Log.i("Readly", …)`; injected
 *   so JVM tests don't touch `android.util.Log`.
 */
class VoiceModelStore(
    private val baseDir: File,
    private val urlOpener: (String) -> DownloadConnection,
    private val expectedSha256: String = ARCHIVE_SHA256,
    private val expectedSizeBytes: Long = ARCHIVE_SIZE_BYTES,
    private val onInfo: (String) -> Unit = { android.util.Log.i("Readly", it) },
) {
    companion object {
        const val MODEL_NAME = "kokoro-en-v0_19"
        const val ARCHIVE_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
            "kokoro-en-v0_19.tar.bz2"
        const val ARCHIVE_SIZE_BYTES = 319625534L
        const val ARCHIVE_SHA256 =
            "912804855a04745fa77a30be545b3f9a5d15c4d66db00b88cbcd4921df605ac7"
        const val REQUIRED_FREE_BYTES = 500L * 1024 * 1024
        /** Previous int8 voice dir, removed after a successful install of the fp32 voice. */
        const val LEGACY_MODEL_NAME = "kokoro-int8-en-v0_19"
        /** [ARCHIVE_SIZE_BYTES] in decimal MB, rounded; the progress label shows this total. */
        val ARCHIVE_SIZE_MB: Int = ((ARCHIVE_SIZE_BYTES + 500_000) / 1_000_000).toInt()

        private const val COMPLETE_MARKER = ".complete"
        private const val READ_BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_MIN_DELTA_BYTES = 256L * 1024

        /** Production [VoiceModelStore] opener backed by [HttpURLConnection]. */
        fun httpUrlOpener(url: String): DownloadConnection = HttpDownloadConnection(url)
    }

    /** Installed model dir (`<base>/kokoro-en-v0_19`); valid once [isInstalled]. */
    val modelDir: File
        get() = File(baseDir, MODEL_NAME)

    /** True if the previous int8 voice dir is still present (pending migration). */
    fun hasLegacyModel(): Boolean = File(baseDir, LEGACY_MODEL_NAME).exists()

    /** True only if the model dir holds the marker, the three files and the data dir. */
    fun isInstalled(): Boolean {
        val dir = File(baseDir, MODEL_NAME)
        return File(dir, COMPLETE_MARKER).isFile &&
            File(dir, "model.onnx").isFile &&
            File(dir, "voices.bin").isFile &&
            File(dir, "tokens.txt").isFile &&
            File(dir, "espeak-ng-data").isDirectory
    }

    /**
     * Downloads, verifies and installs the voice model in a single pass: download, hash
     * and unpack run at the same time, so bzip2 decoding overlaps the network.
     *
     * @param progress called as `(stage, bytesDone, bytesTotal)`; totals are the expected
     *   archive size ([ARCHIVE_SIZE_BYTES] in production). DOWNLOADING reports compressed
     *   bytes consumed, throttled to at most every 256 KB plus once at the end; VERIFYING
     *   is reported once for the final size and hash checks.
     * @param cancelled polled during download, extraction and draining; abort throws
     *   [CancellationException].
     * @throws IOException with a specific message on any failure (space, HTTP, truncation,
     *   hash, unsafe path).
     */
    fun install(progress: (InstallStage, Long, Long) -> Unit, cancelled: () -> Boolean) {
        baseDir.mkdirs()
        val usable = baseDir.usableSpace
        if (usable < REQUIRED_FREE_BYTES) {
            throw IOException(
                "Not enough free space: need 500 MB, have ${usable / 1024 / 1024} MB",
            )
        }
        if (cancelled()) throw CancellationException("Voice download cancelled")
        val tmpDir = File(baseDir, "$MODEL_NAME.tmp")
        val finalDir = File(baseDir, MODEL_NAME)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val consumed = streamDownloadAndUnpack(tmpDir, digest, progress, cancelled)
            progress(InstallStage.VERIFYING, consumed, expectedSizeBytes)
            if (consumed != expectedSizeBytes) {
                throw IOException(
                    "Voice download is incomplete: expected $expectedSizeBytes bytes, " +
                        "got $consumed",
                )
            }
            verify(digest)
            File(tmpDir, COMPLETE_MARKER).createNewFile()
            if (finalDir.exists()) finalDir.deleteRecursively()
            if (!tmpDir.renameTo(finalDir)) {
                throw IOException("Voice install failed: could not move $tmpDir to $finalDir")
            }
            if (!isInstalled()) {
                throw IOException("Voice install failed: archive is missing model files")
            }
            deleteLegacyModel()
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /**
     * Single pass: reads the archive from HTTP through the hash into the bzip2/tar
     * decoders and extracts into [tmpDir]. No `download.part` is written, which halves the
     * disk needed. Returns the compressed bytes consumed.
     */
    private fun streamDownloadAndUnpack(
        tmpDir: File,
        digest: MessageDigest,
        progress: (InstallStage, Long, Long) -> Unit,
        cancelled: () -> Boolean,
    ): Long {
        tmpDir.deleteRecursively()
        tmpDir.mkdirs()
        val base = tmpDir.toPath().toAbsolutePath().normalize()
        urlOpener(ARCHIVE_URL).use { conn ->
            if (conn.statusCode !in 200..299) {
                val detail = if (conn.statusMessage.isBlank()) "" else " ${conn.statusMessage}"
                throw IOException(
                    "Voice download failed: HTTP ${conn.statusCode}$detail from $ARCHIVE_URL",
                )
            }
            // Buffered: commons-compress pulls its input one byte at a time, which is ~5x
            // slower unbuffered (round 9 host benchmark: 1.6 vs 8.7 MB/s). The digest and
            // the counter sit above the buffer so they see every compressed byte consumed.
            val counting = CountingInputStream(
                DigestInputStream(
                    BufferedInputStream(conn.input(), READ_BUFFER_BYTES),
                    digest,
                ),
            )
            var lastReported = 0L
            fun maybeReport() {
                if (counting.count - lastReported >= PROGRESS_MIN_DELTA_BYTES) {
                    progress(InstallStage.DOWNLOADING, counting.count, expectedSizeBytes)
                    lastReported = counting.count
                }
            }
            // Our own content errors, rethrown as-is; any other IOException out of the
            // decoders means a truncated or corrupt stream (handled in the catch below).
            var contentError: IOException? = null
            fun fail(message: String): Nothing {
                val e = IOException(message)
                contentError = e
                throw e
            }
            // bz/tar are pure in-memory wrappers; closing `counting` releases the chain.
            try {
                val tar = TarArchiveInputStream(BZip2CompressorInputStream(counting))
                val buf = ByteArray(READ_BUFFER_BYTES)
                var entry = tar.nextEntry
                while (entry != null) {
                    if (cancelled()) throw CancellationException("Voice download cancelled")
                    val target = base.resolve(stripTopDir(entry.name)).normalize()
                    if (!target.startsWith(base)) {
                        fail("Unsafe path in voice archive: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        target.toFile().mkdirs()
                    } else if (entry.isFile) {
                        target.parent?.toFile()?.mkdirs()
                        target.toFile().outputStream().use { out ->
                            while (true) {
                                if (cancelled()) {
                                    throw CancellationException("Voice download cancelled")
                                }
                                val n = tar.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                maybeReport()
                            }
                        }
                    } else {
                        fail("Unsupported entry in voice archive: ${entry.name}")
                    }
                    maybeReport()
                    entry = tar.nextEntry
                }
                drain(counting, cancelled)
                if (counting.count != lastReported) {
                    progress(InstallStage.DOWNLOADING, counting.count, expectedSizeBytes)
                }
                return counting.count
            } catch (e: IOException) {
                if (e === contentError) throw e
                // The decoder failed: either the server closed early (truncated) or the
                // bytes are corrupt. Drain what the transport still holds, then decide by
                // the byte count.
                var drainError: IOException? = null
                try {
                    drain(counting, cancelled)
                } catch (d: IOException) {
                    drainError = d
                }
                if (counting.count != expectedSizeBytes) {
                    val incomplete = IOException(
                        "Voice download is incomplete: expected $expectedSizeBytes bytes, " +
                            "got ${counting.count}",
                    )
                    if (drainError != null) incomplete.addSuppressed(drainError)
                    throw incomplete
                }
                if (drainError != null) e.addSuppressed(drainError)
                throw e
            } finally {
                counting.close()
            }
        }
    }

    /** Reads [counting] to EOF so the digest covers the whole archive. */
    private fun drain(counting: CountingInputStream, cancelled: () -> Boolean) {
        val buf = ByteArray(READ_BUFFER_BYTES)
        while (true) {
            if (cancelled()) throw CancellationException("Voice download cancelled")
            if (counting.read(buf) < 0) break
        }
    }

    private fun verify(digest: MessageDigest) {
        val actual = digest.digest().toHex()
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            throw IOException(
                "Voice download is corrupted: expected SHA-256 $expectedSha256, got $actual",
            )
        }
    }

    /** Removes the previous int8 voice after a successful install, if still present. */
    private fun deleteLegacyModel() {
        val legacy = File(baseDir, LEGACY_MODEL_NAME)
        if (legacy.exists()) {
            legacy.deleteRecursively()
            onInfo("Removed old voice model $LEGACY_MODEL_NAME")
        }
    }

    /** Strips the archive's single top dir (`kokoro-en-v0_19/`); other names pass through. */
    private fun stripTopDir(name: String): String {
        val clean = name.removePrefix("./")
        val prefix = "$MODEL_NAME/"
        return if (clean.startsWith(prefix)) clean.removePrefix(prefix) else clean
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private class HttpDownloadConnection(url: String) : DownloadConnection {
        private val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }

        override val statusCode: Int
            get() = conn.responseCode
        override val statusMessage: String
            get() = conn.responseMessage ?: ""
        override fun input(): InputStream = conn.inputStream
        override fun close() = conn.disconnect()
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count += n
            return n
        }

        override fun read(): Int {
            val n = super.read()
            if (n >= 0) count++
            return n
        }
    }
}
