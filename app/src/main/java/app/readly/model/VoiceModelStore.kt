package app.readly.model

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
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
    /** Start byte of the response's `Content-Range` header, or null when absent. */
    val contentRangeStart: Long?
    fun input(): InputStream
}

/**
 * Downloads, verifies and unpacks one [VoicePack].
 *
 * Plain JVM Kotlin with no Android types, so it is unit-testable on the host JVM.
 *
 * @param pack the voice pack this store installs.
 * @param baseDir directory holding the download and the installed model
 *   (on device: `context.noBackupFilesDir/voices`).
 * @param urlOpener opens [VoicePack.url] from the given byte offset (0 = whole
 *   file, no `Range` header); production passes [httpUrlOpener].
 * @param expectedSha256 expected SHA-256 of the archive; a parameter so tests can inject
 *   fixture hashes (production uses the pack's [VoicePack.sha256]).
 * @param expectedSizeBytes expected archive size in bytes; a parameter so tests can inject
 *   fixture sizes (production uses the pack's [VoicePack.sizeBytes]).
 * @param onInfo receives messages that production logs with `Log.i("Readly", …)`; injected
 *   so JVM tests don't touch `android.util.Log`.
 * @param sleeper backs the resume back-off; injected so tests don't wait.
 */
class VoiceModelStore(
    val pack: VoicePack,
    private val baseDir: File,
    private val urlOpener: (String, Long) -> DownloadConnection,
    private val expectedSha256: String = pack.sha256,
    private val expectedSizeBytes: Long = pack.sizeBytes,
    private val onInfo: (String) -> Unit = { android.util.Log.i("Readly", it) },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    companion object {
        const val REQUIRED_FREE_BYTES = 500L * 1024 * 1024

        private const val COMPLETE_MARKER = ".complete"
        private const val READ_BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_MIN_DELTA_BYTES = 256L * 1024

        /** Back-off between single-file resume attempts (5 retries, then give up). */
        private val RESUME_DELAYS_MS = longArrayOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L)

        /** Production [VoiceModelStore] opener backed by [HttpURLConnection]. */
        fun httpUrlOpener(url: String, fromByte: Long): DownloadConnection =
            HttpDownloadConnection(url, fromByte)

        /** Ids of the packs installed under [base], in catalog order. */
        fun installedIds(base: File): List<String> =
            VoiceCatalog.ALL.filter { isPackInstalled(base, it) }.map { it.id }

        /**
         * True when any voice pack is installed under [base]. Setup gates on
         * this, not on the main voice: a Persian-only install (Pocket, no
         * English voice) is a working setup.
         */
        fun anyInstalled(base: File): Boolean = installedIds(base).isNotEmpty()

        /** File-level install check, shared by [VoiceModelStore.isInstalled]. */
        internal fun isPackInstalled(base: File, pack: VoicePack): Boolean {
            val dir = File(base, pack.topDir)
            if (!File(dir, COMPLETE_MARKER).isFile) return false
            if (requiredFiles(pack).any { !File(dir, it).isFile }) return false
            return pack.family == VoiceFamily.SUPERTONIC ||
                pack.family == VoiceFamily.POCKET ||
                File(dir, "espeak-ng-data").isDirectory
        }

        private fun requiredFiles(pack: VoicePack): List<String> = when (pack.family) {
            VoiceFamily.KOKORO, VoiceFamily.KITTEN ->
                listOf(pack.modelFile, "voices.bin", "tokens.txt")
            VoiceFamily.PIPER -> listOf(pack.modelFile, "tokens.txt")
            VoiceFamily.POCKET -> listOf(pack.modelFile)
            VoiceFamily.SUPERTONIC -> listOf(
                "duration_predictor.int8.onnx",
                "text_encoder.int8.onnx",
                "vector_estimator.int8.onnx",
                "vocoder.int8.onnx",
                "tts.json",
                "unicode_indexer.bin",
                "voice.bin",
            )
        }
    }

    /** Installed model dir (`<base>/<topDir>`); valid once [isInstalled]. */
    val modelDir: File
        get() = File(baseDir, pack.topDir)

    /** True only if the model dir holds the marker and the family's required files. */
    fun isInstalled(): Boolean = isPackInstalled(baseDir, pack)

    /** Removes this pack's installed dir, if present. */
    fun delete() {
        val dir = File(baseDir, pack.topDir)
        if (dir.exists()) {
            dir.deleteRecursively()
            onInfo("Removed voice pack ${pack.id}")
        }
    }

    /**
     * Downloads, verifies and installs the voice pack in a single pass: download, hash
     * and unpack run at the same time, so bzip2 decoding overlaps the network.
     * Single-file packs ([VoicePack.fileUrl]) stream straight to
     * `<tmpDir>/<modelFile>` instead of being unpacked; the size, hash, marker
     * and rename steps below are shared.
     *
     * @param progress called as `(stage, bytesDone, bytesTotal)`; totals are the expected
     *   download size (the pack's [VoicePack.sizeBytes] in production). DOWNLOADING reports
     *   bytes consumed, throttled to at most every 256 KB plus once at the end;
     *   VERIFYING is reported once for the final size and hash checks.
     * @param cancelled polled during download, extraction and draining; abort throws
     *   [CancellationException].
     * @throws IOException with a specific message on any failure (space, HTTP, truncation,
     *   hash, unsafe path).
     */
    fun install(progress: (InstallStage, Long, Long) -> Unit, cancelled: () -> Boolean) {
        if (!pack.downloadable) {
            throw IOException(
                "${pack.displayName} can't be downloaded yet: copy ${pack.modelFile} to the " +
                    "phone by hand (see tools/pocket-export/README.md)",
            )
        }
        baseDir.mkdirs()
        val usable = baseDir.usableSpace
        if (usable < REQUIRED_FREE_BYTES) {
            throw IOException(
                "Not enough free space: need 500 MB, have ${usable / 1024 / 1024} MB",
            )
        }
        if (cancelled()) throw CancellationException("Voice download cancelled")
        val tmpDir = File(baseDir, "${pack.topDir}.tmp")
        val finalDir = File(baseDir, pack.topDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val consumed = if (pack.fileUrl != null) {
                streamDownloadToFile(tmpDir, digest, progress, cancelled)
            } else {
                streamDownloadAndUnpack(tmpDir, digest, progress, cancelled)
            }
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
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /**
     * Single pass for single-file packs: streams HTTP through the hash into
     * `<tmpDir>/<modelFile>`. Same progress throttle and cancel checks as the
     * archive path. Returns the bytes consumed.
     *
     * A dropped connection resumes instead of restarting: when a read throws an
     * [IOException], this waits out the back-off (cancel-aware), reopens from
     * the bytes already in the file, and appends, keeping the same digest — so
     * it covers exactly the file's bytes. A 206 whose `Content-Range` start
     * matches the offset continues; a 200 (no range support) truncates the
     * file, resets the digest and restarts from 0. After 5 failed resumes the
     * original error is rethrown with the retry count.
     */
    private fun streamDownloadToFile(
        tmpDir: File,
        digest: MessageDigest,
        progress: (InstallStage, Long, Long) -> Unit,
        cancelled: () -> Boolean,
    ): Long {
        tmpDir.deleteRecursively()
        tmpDir.mkdirs()
        val target = File(tmpDir, pack.modelFile)
        target.parentFile?.mkdirs()
        var done = 0L
        var lastReported = 0L
        var resumeFrom = 0L
        var failures = 0
        while (true) {
            if (cancelled()) throw CancellationException("Voice download cancelled")
            var retry = false
            urlOpener(pack.url, resumeFrom).use { conn ->
                if (resumeFrom == 0L) {
                    throwIfHttpError(conn)
                } else {
                    when {
                        conn.statusCode == 206 && conn.contentRangeStart == resumeFrom -> Unit
                        conn.statusCode == 200 -> {
                            digest.reset()
                            done = 0L
                            lastReported = 0L
                            resumeFrom = 0L
                        }
                        else -> {
                            throwIfHttpError(conn)
                            throw IOException(
                                "Voice download failed: HTTP ${conn.statusCode} with an " +
                                    "unexpected Content-Range for resume from $resumeFrom bytes",
                            )
                        }
                    }
                }
                val counting = countedDigestStream(conn, digest)
                try {
                    FileOutputStream(target, resumeFrom > 0L).use { out ->
                        val buf = ByteArray(READ_BUFFER_BYTES)
                        while (true) {
                            if (cancelled()) throw CancellationException("Voice download cancelled")
                            val n = counting.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastReported >= PROGRESS_MIN_DELTA_BYTES) {
                                progress(InstallStage.DOWNLOADING, done, expectedSizeBytes)
                                lastReported = done
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    failures++
                    if (failures > RESUME_DELAYS_MS.size) {
                        throw IOException(
                            "Voice download failed after ${RESUME_DELAYS_MS.size} retries at " +
                                "$done of $expectedSizeBytes bytes: " +
                                "${e.javaClass.simpleName}: ${e.message}",
                            e,
                        )
                    }
                    onInfo(
                        "Voice download interrupted at $done of $expectedSizeBytes bytes " +
                            "(${e.javaClass.simpleName}: ${e.message}); " +
                            "resuming (attempt $failures of ${RESUME_DELAYS_MS.size})",
                    )
                    cancelAwareSleep(RESUME_DELAYS_MS[failures - 1], cancelled)
                    resumeFrom = done
                    retry = true
                } finally {
                    counting.close()
                }
            }
            if (!retry) {
                if (done != lastReported) {
                    progress(InstallStage.DOWNLOADING, done, expectedSizeBytes)
                }
                return done
            }
        }
    }

    /** [sleeper] that aborts with [CancellationException] when [cancelled] fires. */
    private fun cancelAwareSleep(ms: Long, cancelled: () -> Boolean) {
        if (cancelled()) throw CancellationException("Voice download cancelled")
        try {
            sleeper(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Voice download cancelled")
        }
        if (cancelled()) throw CancellationException("Voice download cancelled")
    }

    /** Throws the shared HTTP-failure message unless [conn] is 2xx. */
    private fun throwIfHttpError(conn: DownloadConnection) {
        if (conn.statusCode !in 200..299) {
            val detail = if (conn.statusMessage.isBlank()) "" else " ${conn.statusMessage}"
            throw IOException(
                "Voice download failed: HTTP ${conn.statusCode}$detail from ${pack.url}",
            )
        }
    }

    /**
     * HTTP bytes through the hash with a byte counter on top. Buffered:
     * commons-compress pulls its input one byte at a time, which is ~5x slower
     * unbuffered (round 9 host benchmark: 1.6 vs 8.7 MB/s). The digest and the
     * counter sit above the buffer so they see every byte consumed.
     */
    private fun countedDigestStream(conn: DownloadConnection, digest: MessageDigest): CountingInputStream =
        CountingInputStream(
            DigestInputStream(
                BufferedInputStream(conn.input(), READ_BUFFER_BYTES),
                digest,
            ),
        )

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
        urlOpener(pack.url, 0).use { conn ->
            throwIfHttpError(conn)
            val counting = countedDigestStream(conn, digest)
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

    /** Strips the archive's single top dir (`<topDir>/`); other names pass through. */
    private fun stripTopDir(name: String): String {
        val clean = name.removePrefix("./")
        val prefix = "${pack.topDir}/"
        return if (clean.startsWith(prefix)) clean.removePrefix(prefix) else clean
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private class HttpDownloadConnection(url: String, fromByte: Long) : DownloadConnection {
        private val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            if (fromByte > 0) setRequestProperty("Range", "bytes=$fromByte-")
        }

        override val statusCode: Int
            get() = conn.responseCode
        override val statusMessage: String
            get() = conn.responseMessage ?: ""
        override val contentRangeStart: Long?
            get() = parseContentRangeStart(conn.getHeaderField("Content-Range"))
        override fun input(): InputStream = conn.inputStream
        override fun close() = conn.disconnect()

        companion object {
            /** Start byte of a `Content-Range: bytes <start>-<end>/<total>` value. */
            private fun parseContentRangeStart(value: String?): Long? {
                val range = value?.trim()?.removePrefix("bytes ")?.substringBefore('-') ?: return null
                return range.toLongOrNull()
            }
        }
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
