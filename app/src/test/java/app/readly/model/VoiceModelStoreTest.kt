package app.readly.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceModelStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun isInstalled_freshDir_returnsFalse() {
        val store = VoiceModelStore(temp.newFolder("voices"), { error("unused") }, "unused")
        assertFalse(store.isInstalled())
    }

    @Test
    fun install_success_installsModelAndCleansUp() {
        val archive = fixtureArchive(modelEntries())
        val base = temp.newFolder("voices")
        val store = VoiceModelStore(base, fakeOpener(archive), sha256(archive))
        assertFalse(store.isInstalled())

        val stages = mutableListOf<InstallStage>()
        store.install(progress = { stage, _, _ -> stages.add(stage) }, cancelled = { false })

        assertTrue(store.isInstalled())
        assertTrue(File(base, "kokoro-int8-en-v0_19/model.int8.onnx").isFile)
        assertTrue(File(base, "kokoro-int8-en-v0_19/voices.bin").isFile)
        assertTrue(File(base, "kokoro-int8-en-v0_19/tokens.txt").isFile)
        assertTrue(File(base, "kokoro-int8-en-v0_19/espeak-ng-data").isDirectory)
        assertTrue(File(base, "kokoro-int8-en-v0_19/.complete").isFile)
        assertFalse(File(base, "download.part").exists())
        assertFalse(File(base, "kokoro-int8-en-v0_19.tmp").exists())
        assertTrue(stages.contains(InstallStage.DOWNLOADING))
        assertTrue(stages.contains(InstallStage.VERIFYING))
        assertTrue(stages.contains(InstallStage.UNPACKING))
    }

    @Test
    fun install_wrongHash_throwsExactMessageAndCleansUp() {
        val archive = fixtureArchive(modelEntries())
        val actual = sha256(archive)
        val wrong = (if (actual[0] == '0') "1" else "0") + actual.substring(1)
        val base = temp.newFolder("voices")
        val store = VoiceModelStore(base, fakeOpener(archive), wrong)

        try {
            store.install(progress = { _, _, _ -> }, cancelled = { false })
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("Voice download is corrupted: expected SHA-256 $wrong, got $actual", e.message)
        }
        assertFalse(File(base, "download.part").exists())
        assertFalse(File(base, "kokoro-int8-en-v0_19.tmp").exists())
        assertFalse(store.isInstalled())
    }

    @Test
    fun install_zipSlipEntry_rejectsAndCleansUp() {
        val archive = fixtureArchive(mapOf("../evil" to "evil".toByteArray()))
        val base = temp.newFolder("voices")
        val store = VoiceModelStore(base, fakeOpener(archive), sha256(archive))

        try {
            store.install(progress = { _, _, _ -> }, cancelled = { false })
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("Unsafe path in voice archive: ../evil", e.message)
        }
        assertFalse(File(temp.root, "evil").exists())
        assertFalse(File(base, "download.part").exists())
        assertFalse(File(base, "kokoro-int8-en-v0_19.tmp").exists())
        assertFalse(store.isInstalled())
    }

    @Test
    fun install_cancelledMidDownload_throwsAndCleansUp() {
        val archive = fixtureArchive(modelEntries())
        val base = temp.newFolder("voices")
        val store = VoiceModelStore(base, fakeOpener(archive), sha256(archive))

        var progressCalls = 0
        try {
            store.install(
                progress = { _, _, _ -> progressCalls++ },
                cancelled = { progressCalls >= 1 },
            )
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("Voice download cancelled", e.message)
        }
        assertFalse(File(base, "download.part").exists())
        assertFalse(File(base, "kokoro-int8-en-v0_19.tmp").exists())
        assertFalse(store.isInstalled())
    }

    @Test
    fun install_largeArchive_throttlesProgressTo256KBPlusEndOfStage() {
        // ~300 KB of incompressible fixture data: big enough to cross one 256 KB
        // threshold, small enough to stay under two.
        val padding = ByteArray(300_000)
        java.util.Random(42).nextBytes(padding)
        val archive = fixtureArchive(modelEntries() + ("kokoro-int8-en-v0_19/padding.bin" to padding))
        val base = temp.newFolder("voices")
        val store = VoiceModelStore(base, fakeOpener(archive), sha256(archive))

        val downloading = mutableListOf<Long>()
        val unpacking = mutableListOf<Long>()
        val verifying = mutableListOf<Long>()
        store.install(
            progress = { stage, done, _ ->
                when (stage) {
                    InstallStage.DOWNLOADING -> downloading.add(done)
                    InstallStage.VERIFYING -> verifying.add(done)
                    InstallStage.UNPACKING -> unpacking.add(done)
                }
            },
            cancelled = { false },
        )

        assertTrue(store.isInstalled())
        // One mid-stage report (first 64 KB read past 256 KB) plus end-of-stage.
        assertEquals(2, downloading.size)
        assertEquals(archive.size.toLong(), downloading.last())
        assertEquals(1, verifying.size)
        // Unpacking streams ~300 KB through bzip2: one mid-stage report at most
        // (whether the decoder has buffered the tail by then), plus end-of-stage.
        // Unthrottled this would be one report per entry (5).
        assertTrue("unpacking reports: ${unpacking.size}", unpacking.size in 1..2)
    }

    @Test
    fun install_http404_throwsExactMessage() {
        val base = temp.newFolder("voices")
        val store = VoiceModelStore(
            base,
            { FakeConnection(404, "Not Found", ByteArray(0)) },
            VoiceModelStore.ARCHIVE_SHA256,
        )

        try {
            store.install(progress = { _, _, _ -> }, cancelled = { false })
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals(
                "Voice download failed: HTTP 404 Not Found from ${VoiceModelStore.ARCHIVE_URL}",
                e.message,
            )
        }
        assertFalse(File(base, "download.part").exists())
        assertFalse(File(base, "kokoro-int8-en-v0_19.tmp").exists())
        assertFalse(store.isInstalled())
    }

    private fun fakeOpener(archive: ByteArray): (String) -> DownloadConnection =
        { FakeConnection(200, "OK", archive) }

    private class FakeConnection(
        override val statusCode: Int,
        override val statusMessage: String,
        private val bytes: ByteArray,
    ) : DownloadConnection {
        override fun input(): InputStream = ByteArrayInputStream(bytes)
        override fun close() {}
    }

    private fun modelEntries(): Map<String, ByteArray> = mapOf(
        "kokoro-int8-en-v0_19/model.int8.onnx" to "fake-model".toByteArray(),
        "kokoro-int8-en-v0_19/voices.bin" to "fake-voices".toByteArray(),
        "kokoro-int8-en-v0_19/tokens.txt" to "fake-tokens".toByteArray(),
        "kokoro-int8-en-v0_19/espeak-ng-data/intonation" to "fake-data".toByteArray(),
    )

    private fun fixtureArchive(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        BZip2CompressorOutputStream(out).use { bz ->
            TarArchiveOutputStream(bz).use { tar ->
                for ((name, data) in entries) {
                    val entry = TarArchiveEntry(name)
                    entry.size = data.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(data)
                    tar.closeArchiveEntry()
                }
            }
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
