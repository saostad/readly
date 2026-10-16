package app.readly.model

import java.io.File

/** Loads the committed `piper_voices.json` into [VoiceCatalog] for JVM tests. */
object TestVoices {
    fun loadCatalog() {
        VoiceCatalog.loadForTests(jsonFile().inputStream())
    }

    fun loadEntries(): List<PiperVoiceEntry> =
        PiperCatalogJson.parse(jsonFile().readText())

    private fun jsonFile(): File {
        // Unit tests run from the module dir (`app/`), but fall back to the repo
        // root so the check also works when run from there.
        val candidates = listOf(
            "src/main/assets/${PiperCatalogJson.ASSET_NAME}",
            "app/src/main/assets/${PiperCatalogJson.ASSET_NAME}",
        )
        return candidates.map(::File).firstOrNull { it.isFile }
            ?: error("missing ${PiperCatalogJson.ASSET_NAME} (tried $candidates)")
    }
}
