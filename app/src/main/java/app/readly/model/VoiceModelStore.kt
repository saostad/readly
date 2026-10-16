package app.readly.model

import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** Stages of [VoiceModelStore.install], reported through its progress callback. */
enum class InstallStage {
    DOWNLOADING,
    VERIFYING,
    UNPACKING,
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
 */
class VoiceModelStore(
    private val baseDir: File,
    private val urlOpener: (String) -> DownloadConnection,
    private val expectedSha256: String = ARCHIVE_SHA256,
) {
    companion object {
        const val MODEL_NAME = "kokoro-int8-en-v0_19"
        const val ARCHIVE_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
            "kokoro-int8-en-v0_19.tar.bz2"
        const val ARCHIVE_SIZE_BYTES = 103248205L
        const val ARCHIVE_SHA256 =
            "c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd"
        const val REQUIRED_FREE_BYTES = 400L * 1024 * 1024

        private const val PART_NAME = "download.part"
        private const val COMPLETE_MARKER = ".complete"
        private const val READ_BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_MIN_DELTA_BYTES = 256L * 1024

        /** Production [VoiceModelStore] opener backed by [HttpURLConnection]. */
        fun httpUrlOpener(url: String): DownloadConnection = HttpDownloadConnection(url)
    }

    /** True only if the model dir holds the marker, the three files and the data dir. */
    fun isInstalled(): Boolean {
        val dir = File(baseDir, MODEL_NAME)
        return File(dir, COMPLETE_MARKER).isFile &&
            File(dir, "model.int8.onnx").isFile &&
            File(dir, "voices.bin").isFile &&
            File(dir, "tokens.txt").isFile &&
            File(dir, "espeak-ng-data").isDirectory
    }

    /**
     * Downloads, verifies and installs the voice model.
     *
     * @param progress called as `(stage, bytesDone, bytesTotal)`; totals are [ARCHIVE_SIZE_BYTES].
     *   Throttled: at most every 256 KB within a stage, plus once at the end of each stage.
     * @param cancelled polled during download and unpacking; abort throws [CancellationException].
     * @throws IOException with a specific message on any failure (space, HTTP, hash, unsafe path).
     */
    fun install(progress: (InstallStage, Long, Long) -> Unit, cancelled: () -> Boolean) {
        baseDir.mkdirs()
        val usable = baseDir.usableSpace
        if (usable < REQUIRED_FREE_BYTES) {
            throw IOException(
                "Not enough free space: need 400 MB, have ${usable / 1024 / 1024} MB",
            )
        }
        if (cancelled()) throw CancellationException("Voice download cancelled")
        val partFile = File(baseDir, PART_NAME)
        val tmpDir = File(baseDir, "$MODEL_NAME.tmp")
        val finalDir = File(baseDir, MODEL_NAME)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val downloaded = download(partFile, digest, progress, cancelled)
            progress(InstallStage.VERIFYING, downloaded, ARCHIVE_SIZE_BYTES)
            verify(digest)
            unpack(partFile, tmpDir, progress, cancelled)
            File(tmpDir, COMPLETE_MARKER).createNewFile()
            if (finalDir.exists()) finalDir.deleteRecursively()
            if (!tmpDir.renameTo(finalDir)) {
                throw IOException("Voice install failed: could not move $tmpDir to $finalDir")
            }
            if (!isInstalled()) {
                throw IOException("Voice install failed: archive is missing model files")
            }
        } finally {
            partFile.delete()
            tmpDir.deleteRecursively()
        }
    }

    /** Streams the archive to [partFile] while hashing; returns the bytes downloaded. */
    private fun download(
        partFile: File,
        digest: MessageDigest,
        progress: (InstallStage, Long, Long) -> Unit,
        cancelled: () -> Boolean,
    ): Long {
        urlOpener(ARCHIVE_URL).use { conn ->
            if (conn.statusCode !in 200..299) {
                val detail = if (conn.statusMessage.isBlank()) "" else " ${conn.statusMessage}"
                throw IOException(
                    "Voice download failed: HTTP ${conn.statusCode}$detail from $ARCHIVE_URL",
                )
            }
            var done = 0L
            var lastReported = 0L
            partFile.outputStream().use { out ->
                conn.input().use { input ->
                    val buf = ByteArray(READ_BUFFER_BYTES)
                    while (true) {
                        if (cancelled()) throw CancellationException("Voice download cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (done - lastReported >= PROGRESS_MIN_DELTA_BYTES) {
                            progress(InstallStage.DOWNLOADING, done, ARCHIVE_SIZE_BYTES)
                            lastReported = done
                        }
                    }
                }
            }
            if (done != lastReported) {
                progress(InstallStage.DOWNLOADING, done, ARCHIVE_SIZE_BYTES)
            }
            return done
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

    /**
     * Extracts the archive into [tmpDir], stripping the `kokoro-int8-en-v0_19/` top dir so the
     * model files land directly in [tmpDir] (which is then renamed to the final dir).
     */
    private fun unpack(
        partFile: File,
        tmpDir: File,
        progress: (InstallStage, Long, Long) -> Unit,
        cancelled: () -> Boolean,
    ) {
        tmpDir.deleteRecursively()
        tmpDir.mkdirs()
        val base = tmpDir.toPath().toAbsolutePath().normalize()
        val counting = CountingInputStream(partFile.inputStream())
        var lastReported = 0L
        BZip2CompressorInputStream(counting).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    if (cancelled()) throw CancellationException("Voice download cancelled")
                    val target = base.resolve(stripTopDir(entry.name)).normalize()
                    if (!target.startsWith(base)) {
                        throw IOException("Unsafe path in voice archive: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        target.toFile().mkdirs()
                    } else if (entry.isFile) {
                        target.parent?.toFile()?.mkdirs()
                        Files.copy(tar, target, StandardCopyOption.REPLACE_EXISTING)
                    } else {
                        throw IOException("Unsupported entry in voice archive: ${entry.name}")
                    }
                    if (counting.count - lastReported >= PROGRESS_MIN_DELTA_BYTES) {
                        progress(InstallStage.UNPACKING, counting.count, ARCHIVE_SIZE_BYTES)
                        lastReported = counting.count
                    }
                    entry = tar.nextEntry
                }
            }
        }
        if (counting.count != lastReported) {
            progress(InstallStage.UNPACKING, counting.count, ARCHIVE_SIZE_BYTES)
        }
    }

    /** Strips the archive's single top dir (`kokoro-int8-en-v0_19/`); other names pass through. */
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
