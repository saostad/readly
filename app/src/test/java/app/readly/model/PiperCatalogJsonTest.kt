package app.readly.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Validates the generated Piper voice list (`scripts/gen_piper_catalog.py`). */
class PiperCatalogJsonTest {

    private fun load(): List<PiperVoiceEntry> {
        // Unit tests run from the module dir (`app/`), but fall back to the repo
        // root so the check also works when run from there.
        val candidates = listOf(
            "src/main/assets/${PiperCatalogJson.ASSET_NAME}",
            "app/src/main/assets/${PiperCatalogJson.ASSET_NAME}",
        )
        val file = candidates.map(::File).firstOrNull { it.isFile }
            ?: error("missing ${PiperCatalogJson.ASSET_NAME} (tried $candidates)")
        return PiperCatalogJson.parse(file.readText())
    }

    @Test
    fun catalog_isLargeWithUniqueIdsAndValidHashes() {
        val entries = load()
        assertTrue("expected >= 150 Piper voices, got ${entries.size}", entries.size >= 150)
        val ids = entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val hex64 = Regex("[0-9a-f]{64}")
        for (e in entries) {
            assertTrue("${e.id}: bad sha256 ${e.sha256}", hex64.matches(e.sha256))
        }
    }

    @Test
    fun faAmir_mediumAndCompact_exist() {
        val ids = load().map { it.id }.toSet()
        assertTrue("piper-fa_IR-amir-medium" in ids)
        assertTrue("piper-fa_IR-amir-medium-compact" in ids)
    }

    @Test
    fun noFp16Entries() {
        for (e in load()) {
            assertTrue("${e.id}: fp16 in archive", "fp16" !in e.archive)
            assertTrue("${e.id}: fp16 in id", "fp16" !in e.id)
            assertTrue("${e.id}: fp16 in model", "fp16" !in e.modelFile)
        }
    }

    @Test
    fun everyAliasTarget_exists() {
        val ids = load().map { it.id }.toSet()
        for ((old, target) in VoiceCatalog.PIPER_ALIASES) {
            assertTrue("$old -> $target is missing from the JSON", target in ids)
        }
    }
}
