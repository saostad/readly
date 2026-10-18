package app.readly.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceModelStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var kokoro: VoicePack

    @Before
    fun loadCatalog() {
        TestVoices.loadCatalog()
        kokoro = VoiceCatalog.byId(VoiceCatalog.KOKORO_ID)!!
    }

    private val piperTestPack = VoicePack(
        id = "test-piper",
        displayName = "Test Piper",
        variant = "Standard",
        voiceName = "Test (American)",
        family = VoiceFamily.PIPER,
        archive = "test-piper.tar.bz2",
        sizeBytes = 0L,
        sha256 = "unused",
        topDir = "test-piper-1",
        modelFile = "test.onnx",
        speakers = listOf("Default"),
        rtfNote = "test",
        sampleRes = 0,
    )

    @Test
    fun isInstalled_freshDir_returnsFalse() {
        val store = VoiceModelStore(kokoro, temp.newFolder("voices"), { error("unused") }, "unused")
        assertFalse(store.isInstalled())
    }

    @Test
    fun isInstalled_existingKokoroDir_needsNoRedownload() {
        // The owner's phone already has this dir: it must count as the kokoro-en
        // pack without any download.
        val base = temp.newFolder("voices")
        touch(base, "kokoro-en-v0_19/model.onnx")
        touch(base, "kokoro-en-v0_19/voices.bin")
        touch(base, "kokoro-en-v0_19/tokens.txt")
        File(base, "kokoro-en-v0_19/espeak-ng-data").mkdirs()
        touch(base, "kokoro-en-v0_19/.complete")
        val store = VoiceModelStore(kokoro, base, { error("must not download") })

        assertTrue(store.isInstalled())
        assertEquals(File(base, "kokoro-en-v0_19"), store.modelDir)
    }

    @Test
    fun isInstalled_pocketPack_needsOnlyOnnxAndMarker() {
        val pocket = VoiceCatalog.byId("pocket-farsi-v1")!!
        val base = temp.newFolder("voices")
        touch(base, "pocket-farsi-v1/pocket-farsi-v1.onnx")
        val store = VoiceModelStore(pocket, base, { error("must not download") })

        assertFalse(store.isInstalled())
        touch(base, "pocket-farsi-v1/.complete")
        assertTrue(store.isInstalled())
        assertEquals(File(base, "pocket-farsi-v1"), store.modelDir)
    }

    @Test
    fun install_nonDownloadablePack_throwsCopyByHandMessage() {
        // No catalog pack is non-downloadable now; a future pack without a host
        // keeps the copy-by-hand message.
        val pack = piperTestPack.copy(archive = "", fileUrl = null)
        val base = temp.newFolder("voices")
        val store = VoiceModelStore(pack, base, { error("must not download") })

        try {
            store.install(progress = { _, _, _ -> }, cancelled = { false })
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals(
                "Test Piper can't be downloaded yet: copy test.onnx to the " +
                    "phone by hand (see tools/pocket-export/README.md)",
                e.message,
            )
        }
    }

    @Test
    fun pocketPack_isDownloadableFromHuggingFace() {
        val pocket = VoiceCatalog.byId("pocket-farsi-v1")!!
        assertTrue(pocket.downloadable)
        assertEquals(
            "https://huggingface.co/saostad/readly-voices/resolve/main/pocket-farsi-v1/pocket-farsi-v1.onnx",
            pocket.url,
        )
        assertEquals(
            "3f3322396a6f8a65e970438f0a58b146e90cd49283cf8c46cef7f3f877cdca30",
            pocket.sha256,
        )
    }

    @Test
    fun install_singleFile_success_writesModelAndMarker() {
        val pocket = VoiceCatalog.byId("pocket-farsi-v1")!!
        val bytes = "fake-onnx-model".toByteArray()
        val base = temp.newFolder("voices")
        val store = VoiceModelStore(pocket, base, fakeOpener(bytes), sha256(bytes), bytes.size.toLong())
        assertFalse(store.isInstalled())

        val stages = mutableListOf<InstallStage>()
        store.install(progress = { stage, _, _ -> stages.add(stage) }, cancelled = { false })

        assertTrue(store.isInstalled())
        assertEquals("fake-onnx-model", File(base, "pocket-farsi-v1/pocket-farsi-v1.onnx").readText())
        assertTrue(File(base, "pocket-farsi-v1/.complete").isFile)
        assertFalse(File(base, "pocket-farsi-v1.tmp").exists())
        assertTrue(stages.contains(InstallStage.DOWNLOADING))
        assertTrue(stages.contains(InstallStage.VERIFYING))
    }

    @Test
    fun install_singleFile_wrongHash_throwsCorruptedAndCleansUp() {
        val pocket = VoiceCatalog.byId("pocket-farsi-v1")!!
        val bytes = "fake-onnx-model".toByteArray()
        val actual = sha256(bytes)
        val wrong = (if (actual[0] == '0') "1" else "0") + actual.substring(1)
        val base = temp.newFolder("voices")
        val store = VoiceModelStore(pocket, base, fakeOpener(bytes), wrong, bytes.size.toLong())

        try {
            store.install(progress = { _, _, _ -> }, cancelled = { false })
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("Voice download is corrupted: expected SHA-256 $wrong, got $actual", e.message)
        }
        assertFalse(File(base, "pocket-farsi-v1.tmp").exists())
        assertFalse(store.isInstalled())
    }

    @Test
    fun install_singleFile_truncated_throwsIncompleteAndCleansUp() {
        val pocket = VoiceCatalog.byId("pocket-farsi-v1")!!
        val bytes = "fake-onnx-model".toByteArray()
        val base = temp.newFolder("voices")
        val store = VoiceModelStore(
            pocket, base, fakeOpener(bytes), sha256(bytes), bytes.size.toLong() + 10,
        )

        try {
            store.install(progress = { _, _, _ -> }, cancelled = { false })
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals(
                "Voice download is incomplete: expected ${bytes.size + 10} bytes, got ${bytes.size}",
                e.message,
            )
        }
        assertFalse(File(base, "pocket-farsi-v1.tmp").exists())
        assertFalse(store.isInstalled())
    }

    @Test
    fun install_singleFile_http404_includesStatusAndUrl() {
        val pocket = VoiceCatalog.byId("pocket-farsi-v1")!!
        val base = temp.newFolder("voices")
        val store = VoiceModelStore(
            pocket,
            base,
            { FakeConnection(404, "Not Found", ByteArray(0)) },
            pocket.sha256,
        )

        try {
            store.install(progress = { _, _, _ -> }, cancelled = { false })
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals(
                "Voice download failed: HTTP 404 Not Found from ${pocket.url}",
                e.message,
            )
        }
        assertFalse(File(base, "pocket-farsi-v1.tmp").exists())
        assertFalse(store.isInstalled())
    }

    @Test
    fun install_success_installsModelAndCleansUp() {
        val archive = fixtureArchive(modelEntries())
        val base = temp.newFolder("voices")
        val store = VoiceModelStore(kokoro, base, fakeOpener(archive), sha256(archive), archive.size.toLong())
        assertFalse(store.isInstalled())

        val stages = mutableListOf<InstallStage>()
        store.install(progress = { stage, _, _ -> stages.add(stage) }, cancelled = { false })

        assertTrue(store.isInstalled())
        assertTrue(File(base, "kokoro-en-v0_19/model.onnx").isFile)
        assertTrue(File(base, "kokoro-en-v0_19/voices.bin").isFile)
        assertTrue(File(base, "kokoro-en-v0_19/tokens.txt").isFile)
        assertTrue(File(base, "kokoro-en-v0_19/espeak-ng-data").isDirectory)
        assertTrue(File(base, "kokoro-en-v0_19/.complete").isFile)
        assertFalse(File(base, "kokoro-en-v0_19.tmp").exists())
        assertTrue(stages.contains(InstallStage.DOWNLOADING))
        assertTrue(stages.contains(InstallStage.VERIFYING))
    }

    @Test
    fun install_piperPack_usesPackTopDirAndFiles() {
        val archive = fixtureArchive(
            mapOf(
                "test-piper-1/test.onnx" to "fake-model".toByteArray(),
                "test-piper-1/tokens.txt" to "fake-tokens".toByteArray(),
                "test-piper-1/espeak-ng-data/intonation" to "fake-data".toByteArray(),
            ),
        )
        val base = temp.newFolder("voices")
        val store = VoiceModelStore(
            piperTestPack,
            base,
            fakeOpener(archive),
            sha256(archive),
            archive.size.toLong(),
        )

        store.install(progress = { _, _, _ -> }, cancelled = { false })

        assertTrue(store.isInstalled())
        assertEquals(File(base, "test-piper-1"), store.modelDir)
        assertTrue(File(base, "test-piper-1/test.onnx").isFile)
        assertTrue(File(base, "test-piper-1/.complete").isFile)
        assertFalse(File(base, "test-piper-1.tmp").exists())
    }

    @Test
    fun install_wrongHash_throwsExactMessageAndCleansUp() {
        val archive = fixtureArchive(modelEntries())
        val actual = sha256(archive)
        val wrong = (if (actual[0] == '0') "1" else "0") + actual.substring(1)
        val base = temp.newFolder("voices")
        val store = VoiceModelStore(kokoro, base, fakeOpener(archive), wrong, archive.size.toLong())

        try {
            store.install(progress = { _, _, _ -> }, cancelled = { false })
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("Voice download is corrupted: expected SHA-256 $wrong, got $actual", e.message)
        }
        assertFalse(File(base, "kokoro-en-v0_19.tmp").exists())
        assertFalse(store.isInstalled())
    }

    @Test
    fun install_zipSlipEntry_rejectsAndCleansUp() {
        val archive = fixtureArchive(mapOf("../evil" to "evil".toByteArray()))
        val base = temp.newFolder("voices")
        val store = VoiceModelStore(kokoro, base, fakeOpener(archive), sha256(archive), archive.size.toLong())

        try {
            store.install(progress = { _, _, _ -> }, cancelled = { false })
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("Unsafe path in voice archive: ../evil", e.message)
        }
        assertFalse(File(temp.root, "evil").exists())
        assertFalse(File(base, "kokoro-en-v0_19.tmp").exists())
        assertFalse(store.isInstalled())
    }

    @Test
    fun install_cancelledMidDownload_throwsAndCleansUp() {
        // Big enough to cross the 256 KB progress threshold mid-stream, so the
        // cancellation below fires while bytes are still being consumed.
        val padding = ByteArray(300_000)
        java.util.Random(42).nextBytes(padding)
        val archive = fixtureArchive(modelEntries() + ("kokoro-en-v0_19/padding.bin" to padding))
        val base = temp.newFolder("voices")
        val store = VoiceModelStore(kokoro, base, fakeOpener(archive), sha256(archive), archive.size.toLong())

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
        assertTrue("cancel fired mid-stream", progressCalls >= 1)
        assertFalse(File(base, "kokoro-en-v0_19.tmp").exists())
        assertFalse(store.isInstalled())
    }

    @Test
    fun install_cancelledMidLargeEntry_abortsBeforeEntryEnds() {
        // One entry over 1 MB, first in the archive: ~23 chunks of 64 KB.
        val big = ByteArray(1_500_000)
        java.util.Random(7).nextBytes(big)
        val archive = fixtureArchive(
            mapOf("kokoro-en-v0_19/model.onnx" to big) + modelEntries().filterKeys {
                it != "kokoro-en-v0_19/model.onnx"
            },
        )
        val base = temp.newFolder("voices")
        val store = VoiceModelStore(kokoro, base, fakeOpener(archive), sha256(archive), archive.size.toLong())

        val checks = AtomicInteger()
        try {
            store.install(
                progress = { _, _, _ -> },
                cancelled = { checks.incrementAndGet() > 5 },
            )
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("Voice download cancelled", e.message)
        }
        assertTrue("aborted inside the first entry: ${checks.get()}", checks.get() < 15)
        assertFalse(File(base, "kokoro-en-v0_19.tmp").exists())
        assertFalse(store.isInstalled())
    }

    @Test
    fun install_truncatedStream_throwsIncompleteAndCleansUp() {
        val archive = fixtureArchive(modelEntries())
        val half = archive.copyOf(archive.size / 2)
        val base = temp.newFolder("voices")
        val store = VoiceModelStore(kokoro, base, fakeOpener(half), sha256(archive), archive.size.toLong())

        try {
            store.install(progress = { _, _, _ -> }, cancelled = { false })
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals(
                "Voice download is incomplete: expected ${archive.size} bytes, got ${half.size}",
                e.message,
            )
        }
        assertFalse(File(base, "kokoro-en-v0_19.tmp").exists())
        assertFalse(store.isInstalled())
    }

    @Test
    fun install_largeArchive_throttlesProgressTo256KBPlusEndOfStage() {
        // ~1.2 MB of incompressible fixture data: spans two bzip2 blocks, so
        // consumption (and progress) is spread across the download.
        val padding = ByteArray(1_200_000)
        java.util.Random(42).nextBytes(padding)
        val archive = fixtureArchive(modelEntries() + ("kokoro-en-v0_19/padding.bin" to padding))
        val base = temp.newFolder("voices")
        val store = VoiceModelStore(kokoro, base, fakeOpener(archive), sha256(archive), archive.size.toLong())

        val downloading = mutableListOf<Long>()
        val verifying = mutableListOf<Long>()
        store.install(
            progress = { stage, done, _ ->
                when (stage) {
                    InstallStage.DOWNLOADING -> downloading.add(done)
                    InstallStage.VERIFYING -> verifying.add(done)
                }
            },
            cancelled = { false },
        )

        assertTrue(store.isInstalled())
        assertTrue(downloading.isNotEmpty())
        assertEquals(archive.size.toLong(), downloading.last())
        assertEquals(1, verifying.size)
        // Throttled: every gap but the final end-of-stage tail is at least 256 KB.
        // Unthrottled this would report per 64 KB write (~19 reports).
        for (i in 1 until downloading.size - 1) {
            assertTrue(
                "gap $i too small: ${downloading[i] - downloading[i - 1]}",
                downloading[i] - downloading[i - 1] >= 256L * 1024,
            )
        }
        assertTrue(
            "too many reports: ${downloading.size}",
            downloading.size <= archive.size / (256 * 1024) + 2,
        )
    }

    @Test
    fun install_http404_throwsExactMessage() {
        val base = temp.newFolder("voices")
        val store = VoiceModelStore(
            kokoro,
            base,
            { FakeConnection(404, "Not Found", ByteArray(0)) },
            kokoro.sha256,
        )

        try {
            store.install(progress = { _, _, _ -> }, cancelled = { false })
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals(
                "Voice download failed: HTTP 404 Not Found from ${kokoro.url}",
                e.message,
            )
        }
        assertFalse(File(base, "kokoro-en-v0_19.tmp").exists())
        assertFalse(store.isInstalled())
    }

    @Test
    fun archiveSizeMb_matchesDisplayedDownloadTotal() {
        assertEquals(320, kokoro.sizeMb)
    }

    @Test
    fun installedIds_listsOnlyCompletePacks() {
        val base = temp.newFolder("voices")
        touch(base, "kokoro-en-v0_19/model.onnx")
        touch(base, "kokoro-en-v0_19/voices.bin")
        touch(base, "kokoro-en-v0_19/tokens.txt")
        File(base, "kokoro-en-v0_19/espeak-ng-data").mkdirs()
        touch(base, "kokoro-en-v0_19/.complete")
        touch(base, "vits-piper-en_US-lessac-medium/en_US-lessac-medium.onnx")
        touch(base, "vits-piper-en_US-lessac-medium/tokens.txt")
        File(base, "vits-piper-en_US-lessac-medium/espeak-ng-data").mkdirs()
        touch(base, "vits-piper-en_US-lessac-medium/.complete")
        // Incomplete: files but no marker.
        touch(base, "sherpa-onnx-supertonic-tts-int8-2026-03-06/tts.json")

        assertEquals(
            listOf("kokoro-en", "piper-en_US-lessac-medium"),
            VoiceModelStore.installedIds(base),
        )
    }

    @Test
    fun anyInstalled_falseWhenEmpty_trueWhenAnyPackComplete() {
        val base = temp.newFolder("voices")
        assertFalse(VoiceModelStore.anyInstalled(base))
        // Files without the marker don't count.
        touch(base, "pocket-farsi-v1/pocket-farsi-v1.onnx")
        assertFalse(VoiceModelStore.anyInstalled(base))
        touch(base, "pocket-farsi-v1/.complete")
        assertTrue(VoiceModelStore.anyInstalled(base))
    }

    @Test
    fun delete_removesPackDirAndLogs() {
        val base = temp.newFolder("voices")
        touch(base, "kokoro-en-v0_19/model.onnx")
        val infos = mutableListOf<String>()
        val store = VoiceModelStore(kokoro, base, { error("unused") }, onInfo = infos::add)

        store.delete()

        assertFalse(File(base, "kokoro-en-v0_19").exists())
        assertEquals(listOf("Removed voice pack kokoro-en"), infos)
        store.delete()
        assertEquals("second delete logs nothing", 1, infos.size)
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

    private fun touch(base: File, path: String) {
        val file = File(base, path)
        file.parentFile!!.mkdirs()
        file.writeText("x")
    }

    private fun modelEntries(): Map<String, ByteArray> = mapOf(
        "kokoro-en-v0_19/model.onnx" to "fake-model".toByteArray(),
        "kokoro-en-v0_19/voices.bin" to "fake-voices".toByteArray(),
        "kokoro-en-v0_19/tokens.txt" to "fake-tokens".toByteArray(),
        "kokoro-en-v0_19/espeak-ng-data/intonation" to "fake-data".toByteArray(),
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
