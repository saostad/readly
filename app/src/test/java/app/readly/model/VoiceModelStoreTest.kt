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
        val store = VoiceModelStore(kokoro, temp.newFolder("voices"), { _, _ -> error("unused") }, "unused")
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
        val store = VoiceModelStore(kokoro, base, { _, _ -> error("must not download") })

        assertTrue(store.isInstalled())
        assertEquals(File(base, "kokoro-en-v0_19"), store.modelDir)
    }

    @Test
    fun isInstalled_pocketPack_needsOnlyOnnxAndMarker() {
        val pocket = VoiceCatalog.byId("pocket-farsi-v1")!!
        val base = temp.newFolder("voices")
        touch(base, "pocket-farsi-v1/pocket-farsi-v1.onnx")
        val store = VoiceModelStore(pocket, base, { _, _ -> error("must not download") })

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
        val store = VoiceModelStore(pack, base, { _, _ -> error("must not download") })

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
    fun isInstalled_pocketV2Pack_needsOnlyOnnxAndMarker() {
        val pocket = VoiceCatalog.byId("pocket-farsi-v2")!!
        val base = temp.newFolder("voices")
        touch(base, "pocket-farsi-v2/pocket-farsi-v2.onnx")
        val store = VoiceModelStore(pocket, base, { _, _ -> error("must not download") })

        assertFalse(store.isInstalled())
        touch(base, "pocket-farsi-v2/.complete")
        assertTrue(store.isInstalled())
        assertEquals(File(base, "pocket-farsi-v2"), store.modelDir)
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
            { _, _ -> FakeConnection(404, "Not Found", ByteArray(0)) },
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
            { _, _ -> FakeConnection(404, "Not Found", ByteArray(0)) },
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
    fun isInstalled_phonon_needsEveryVoiceFileAndMarker() {
        val phonon = VoiceCatalog.byId("phonon-31m-en")!!
        val base = temp.newFolder("voices")
        fun writeAll() {
            touch(base, "phonon-31m-en/config.json")
            touch(base, "phonon-31m-en/model.q8.gguf")
            touch(base, "phonon-31m-en/tokenizer.json")
            for (name in VoiceCatalog.PHONON_VOICE_FILES) {
                touch(base, "phonon-31m-en/voices/$name.safetensors")
            }
            touch(base, "phonon-31m-en/.complete")
        }

        writeAll()
        assertTrue(VoiceModelStore.isPackInstalled(base, phonon))
        for (name in VoiceCatalog.PHONON_VOICE_FILES) {
            val file = File(base, "phonon-31m-en/voices/$name.safetensors")
            assertTrue(file.delete())
            assertFalse(
                "a missing $name voice file must not count as installed",
                VoiceModelStore.isPackInstalled(base, phonon),
            )
            touch(base, "phonon-31m-en/voices/$name.safetensors")
        }
        assertFalse(File(base, "phonon-31m-en/espeak-ng-data").exists())
        assertTrue(VoiceModelStore.isPackInstalled(base, phonon))
    }

    @Test
    fun install_phononArchive_usesArchiveUrlAndInstalls() {
        val phonon = VoiceCatalog.byId("phonon-31m-en")!!
        // Shaped like the hosted archive: one top dir with the model, the voices, LICENSE and README.
        val entries = mutableMapOf(
            "phonon-31m-en/LICENSE" to "fake-license".toByteArray(),
            "phonon-31m-en/README.md" to "fake-readme".toByteArray(),
            "phonon-31m-en/config.json" to "fake-config".toByteArray(),
            "phonon-31m-en/model.q8.gguf" to "fake-model".toByteArray(),
            "phonon-31m-en/tokenizer.json" to "fake-tokenizer".toByteArray(),
        )
        for (name in VoiceCatalog.PHONON_VOICE_FILES) {
            entries["phonon-31m-en/voices/$name.safetensors"] = "fake-$name".toByteArray()
        }
        val archive = fixtureArchive(entries)
        val base = temp.newFolder("voices")
        val requested = mutableListOf<String>()
        val store = VoiceModelStore(
            phonon,
            base,
            { url, _ ->
                requested.add(url)
                FakeConnection(200, "OK", archive)
            },
            sha256(archive),
            archive.size.toLong(),
        )

        assertFalse(store.isInstalled())
        store.install(progress = { _, _, _ -> }, cancelled = { false })

        assertEquals(listOf(phonon.archiveUrl), requested)
        assertTrue(store.isInstalled())
        assertTrue(File(base, "phonon-31m-en/voices/zoey.safetensors").isFile)
        assertFalse(File(base, "phonon-31m-en.tmp").exists())
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
        val store = VoiceModelStore(kokoro, base, { _, _ -> error("unused") }, onInfo = infos::add)

        store.delete()

        assertFalse(File(base, "kokoro-en-v0_19").exists())
        assertEquals(listOf("Removed voice pack kokoro-en"), infos)
        store.delete()
        assertEquals("second delete logs nothing", 1, infos.size)
    }

    private fun fakeOpener(archive: ByteArray): (String, Long) -> DownloadConnection =
        { _, _ -> FakeConnection(200, "OK", archive) }

    @Test
    fun install_singleFile_dropThenResume206_installsAndOpenerSawOffset() {
        val pocket = VoiceCatalog.byId("pocket-farsi-v1")!!
        val bytes = ByteArray(100) { it.toByte() }
        val offsets = mutableListOf<Long>()
        val infos = mutableListOf<String>()
        val opener: (String, Long) -> DownloadConnection = { _, from ->
            offsets.add(from)
            if (from == 0L) {
                FakeConnection(200, "OK", bytes, failAfterBytes = 10)
            } else {
                FakeConnection(
                    206, "Partial Content", bytes.copyOfRange(from.toInt(), bytes.size),
                    contentRangeStart = from,
                )
            }
        }
        val base = temp.newFolder("voices")
        val store = VoiceModelStore(
            pocket, base, opener, sha256(bytes), bytes.size.toLong(),
            onInfo = infos::add, sleeper = {},
        )

        store.install(progress = { _, _, _ -> }, cancelled = { false })

        assertEquals(listOf(0L, 10L), offsets)
        assertTrue(store.isInstalled())
        assertTrue(bytes.contentEquals(File(base, "pocket-farsi-v1/pocket-farsi-v1.onnx").readBytes()))
        assertTrue(File(base, "pocket-farsi-v1/.complete").isFile)
        assertEquals(
            listOf(
                "Voice download interrupted at 10 of 100 bytes " +
                    "(SocketException: Software caused connection abort); " +
                    "resuming (attempt 1 of 5)",
            ),
            infos,
        )
    }

    @Test
    fun install_singleFile_resumeAnswers200_restartsFromZeroAndInstalls() {
        val pocket = VoiceCatalog.byId("pocket-farsi-v1")!!
        val bytes = ByteArray(100) { (it * 3).toByte() }
        val offsets = mutableListOf<Long>()
        var first = true
        val opener: (String, Long) -> DownloadConnection = { _, from ->
            offsets.add(from)
            if (first) {
                first = false
                FakeConnection(200, "OK", bytes, failAfterBytes = 10)
            } else {
                // No range support: full body, no Content-Range.
                FakeConnection(200, "OK", bytes)
            }
        }
        val base = temp.newFolder("voices")
        val store = VoiceModelStore(
            pocket, base, opener, sha256(bytes), bytes.size.toLong(),
            onInfo = {}, sleeper = {},
        )

        store.install(progress = { _, _, _ -> }, cancelled = { false })

        assertEquals(listOf(0L, 10L), offsets)
        assertTrue(store.isInstalled())
        assertTrue(bytes.contentEquals(File(base, "pocket-farsi-v1/pocket-farsi-v1.onnx").readBytes()))
    }

    @Test
    fun install_singleFile_reopenFailuresThen206_installsWithBackoff() {
        val pocket = VoiceCatalog.byId("pocket-farsi-v1")!!
        val bytes = ByteArray(100) { it.toByte() }
        val offsets = mutableListOf<Long>()
        val slices = mutableListOf<Long>()
        val infos = mutableListOf<String>()
        var resumeCalls = 0
        val opener: (String, Long) -> DownloadConnection = { _, from ->
            offsets.add(from)
            if (from == 0L) {
                FakeConnection(200, "OK", bytes, failAfterBytes = 10)
            } else {
                resumeCalls++
                if (resumeCalls <= 2) {
                    throw java.net.UnknownHostException("Unable to resolve host \"huggingface.co\"")
                }
                FakeConnection(
                    206, "Partial Content", bytes.copyOfRange(from.toInt(), bytes.size),
                    contentRangeStart = from,
                )
            }
        }
        val base = temp.newFolder("voices")
        val store = VoiceModelStore(
            pocket, base, opener, sha256(bytes), bytes.size.toLong(),
            onInfo = infos::add, sleeper = slices::add,
        )

        store.install(progress = { _, _, _ -> }, cancelled = { false })

        assertEquals(listOf(0L, 10L, 10L, 10L), offsets)
        assertTrue(store.isInstalled())
        assertTrue(bytes.contentEquals(File(base, "pocket-farsi-v1/pocket-farsi-v1.onnx").readBytes()))
        assertDelays(slices, listOf(2_000L, 4_000L, 8_000L))
        assertEquals(
            listOf(
                "Voice download interrupted at 10 of 100 bytes " +
                    "(SocketException: Software caused connection abort); " +
                    "resuming (attempt 1 of 5)",
                "Voice download interrupted at 10 of 100 bytes " +
                    "(UnknownHostException: Unable to resolve host \"huggingface.co\"); " +
                    "resuming (attempt 2 of 5)",
                "Voice download interrupted at 10 of 100 bytes " +
                    "(UnknownHostException: Unable to resolve host \"huggingface.co\"); " +
                    "resuming (attempt 3 of 5)",
            ),
            infos,
        )
    }

    @Test
    fun install_singleFile_dropsWithProgressBetween_resetsCounterAndInstalls() {
        val pocket = VoiceCatalog.byId("pocket-farsi-v1")!!
        val bytes = ByteArray(100) { (it * 7).toByte() }
        val offsets = mutableListOf<Long>()
        val slices = mutableListOf<Long>()
        // 6 drops, each delivering 10 new bytes before dropping; the 7th attempt
        // completes. Every failure sees progress, so the counter resets and the
        // install succeeds instead of throwing after 5 retries.
        val opener: (String, Long) -> DownloadConnection = { _, from ->
            offsets.add(from)
            if (from >= 60L) {
                FakeConnection(
                    206, "Partial Content", bytes.copyOfRange(from.toInt(), bytes.size),
                    contentRangeStart = from,
                )
            } else if (from == 0L) {
                FakeConnection(200, "OK", bytes, failAfterBytes = 10)
            } else {
                FakeConnection(
                    206, "Partial Content", bytes.copyOfRange(from.toInt(), bytes.size),
                    contentRangeStart = from, failAfterBytes = 10,
                )
            }
        }
        val base = temp.newFolder("voices")
        val store = VoiceModelStore(
            pocket, base, opener, sha256(bytes), bytes.size.toLong(),
            onInfo = {}, sleeper = slices::add,
        )

        store.install(progress = { _, _, _ -> }, cancelled = { false })

        assertEquals(listOf(0L, 10L, 20L, 30L, 40L, 50L, 60L), offsets)
        assertTrue(store.isInstalled())
        assertTrue(bytes.contentEquals(File(base, "pocket-farsi-v1/pocket-farsi-v1.onnx").readBytes()))
        // A fresh streak each time: every back-off is the first 2 s delay.
        assertDelays(slices, List(6) { 2_000L })
    }

    @Test
    fun install_singleFile_consecutiveFailuresWithNoProgress_throwsFailedAfter5Retries() {
        val pocket = VoiceCatalog.byId("pocket-farsi-v1")!!
        val bytes = ByteArray(100) { it.toByte() }
        val offsets = mutableListOf<Long>()
        val slices = mutableListOf<Long>()
        val opener: (String, Long) -> DownloadConnection = { _, from ->
            offsets.add(from)
            if (from == 0L) {
                FakeConnection(200, "OK", bytes, failAfterBytes = 10)
            } else {
                throw java.net.UnknownHostException("Unable to resolve host \"huggingface.co\"")
            }
        }
        val base = temp.newFolder("voices")
        val store = VoiceModelStore(
            pocket, base, opener, sha256(bytes), bytes.size.toLong(),
            onInfo = {}, sleeper = slices::add,
        )

        try {
            store.install(progress = { _, _, _ -> }, cancelled = { false })
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals(
                "Voice download failed after 5 retries at 10 of 100 bytes: " +
                    "UnknownHostException: Unable to resolve host \"huggingface.co\"",
                e.message,
            )
        }
        assertEquals(listOf(0L, 10L, 10L, 10L, 10L, 10L), offsets)
        assertDelays(slices, listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L))
        assertFalse(File(base, "pocket-farsi-v1").exists())
        assertFalse(File(base, "pocket-farsi-v1.tmp").exists())
        assertFalse(store.isInstalled())
    }

    @Test
    fun install_singleFile_cancelDuringLongBackoff_returnsWithinOneSlice() {
        val pocket = VoiceCatalog.byId("pocket-farsi-v1")!!
        val bytes = ByteArray(100) { it.toByte() }
        val slices = mutableListOf<Long>()
        var cancelled = false
        val opener: (String, Long) -> DownloadConnection = { _, from ->
            if (from == 0L) {
                FakeConnection(200, "OK", bytes, failAfterBytes = 10)
            } else {
                throw java.net.UnknownHostException("Unable to resolve host \"huggingface.co\"")
            }
        }
        val base = temp.newFolder("voices")
        // The first 4 back-offs total 2+4+8+16 = 30 s = 120 slices; cancel one
        // slice into the 30 s back-off.
        val store = VoiceModelStore(
            pocket, base, opener, sha256(bytes), bytes.size.toLong(),
            onInfo = {},
            sleeper = { slice ->
                slices.add(slice)
                if (slices.size == 121) cancelled = true
            },
        )

        try {
            store.install(progress = { _, _, _ -> }, cancelled = { cancelled })
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("Voice download cancelled", e.message)
        }
        assertEquals(121, slices.size)
        assertTrue(slices.all { it in 1..250L })
        assertFalse(File(base, "pocket-farsi-v1").exists())
        assertFalse(File(base, "pocket-farsi-v1.tmp").exists())
        assertFalse(store.isInstalled())
    }

    /** Groups per-slice sleeps into back-offs, asserting each totals its expected delay. */
    private fun assertDelays(slices: List<Long>, expected: List<Long>) {
        var i = 0
        for (delay in expected) {
            var sum = 0L
            while (sum < delay) {
                assertTrue("ran out of slices for delay $delay", i < slices.size)
                assertTrue("slice out of range: ${slices[i]}", slices[i] in 1..250L)
                sum += slices[i]
                i++
            }
            assertEquals(delay, sum)
        }
        assertEquals("extra slices: $slices", i, slices.size)
    }

    @Test
    fun install_singleFile_cancelDuringBackoff_throwsCancellationAndInstallsNothing() {
        val pocket = VoiceCatalog.byId("pocket-farsi-v1")!!
        val bytes = ByteArray(100) { it.toByte() }
        var cancelled = false
        val opener: (String, Long) -> DownloadConnection = { _, from ->
            if (from == 0L) {
                FakeConnection(200, "OK", bytes, failAfterBytes = 10)
            } else {
                FakeConnection(
                    206, "Partial Content", bytes.copyOfRange(from.toInt(), bytes.size),
                    contentRangeStart = from,
                )
            }
        }
        val base = temp.newFolder("voices")
        val store = VoiceModelStore(
            pocket, base, opener, sha256(bytes), bytes.size.toLong(),
            onInfo = {}, sleeper = { cancelled = true },
        )

        try {
            store.install(progress = { _, _, _ -> }, cancelled = { cancelled })
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("Voice download cancelled", e.message)
        }
        assertFalse(File(base, "pocket-farsi-v1").exists())
        assertFalse(File(base, "pocket-farsi-v1.tmp").exists())
        assertFalse(store.isInstalled())
    }

    private class FakeConnection(
        override val statusCode: Int,
        override val statusMessage: String,
        private val bytes: ByteArray,
        override val contentRangeStart: Long? = null,
        private val failAfterBytes: Int = Int.MAX_VALUE,
    ) : DownloadConnection {
        override fun input(): InputStream =
            if (failAfterBytes >= bytes.size) ByteArrayInputStream(bytes)
            else FailingStream(bytes, failAfterBytes)
        override fun close() {}
    }

    /** Serves [bytes] up to [failAfter], then drops like a flaky connection. */
    private class FailingStream(private val bytes: ByteArray, private val failAfter: Int) : InputStream() {
        private var pos = 0

        private fun check() {
            if (pos >= failAfter) throw java.net.SocketException("Software caused connection abort")
        }

        override fun read(): Int {
            check()
            if (pos >= bytes.size) return -1
            return bytes[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            check()
            if (pos >= bytes.size) return -1
            val n = minOf(len, minOf(bytes.size - pos, failAfter - pos))
            bytes.copyInto(b, off, pos, pos + n)
            pos += n
            return n
        }
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
