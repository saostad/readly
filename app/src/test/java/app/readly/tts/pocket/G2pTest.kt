package app.readly.tts.pocket

import java.io.File
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The Kotlin G2P must produce exactly the torch beam-search strings
 * (`g2p_raw` in `src/test/resources/pocket/v2-golden.jsonl`) on every golden
 * sentence. Runs on the host JVM against the real ONNX files in
 * `src/main/assets/ge2pe/` via the host ORT build — no skips: a missing
 * model or an unloadable runtime fails with its path and error.
 */
class G2pTest {
    private val encoderFile: File by lazy { assetFile("encoder.onnx") }
    private val decoderFile: File by lazy { assetFile("decoder.onnx") }

    private fun assetFile(name: String): File {
        val file = File(System.getProperty("user.dir"), "src/main/assets/ge2pe/$name")
        if (!file.isFile) {
            fail("G2P asset is missing: tried ${file.absolutePath} (copy tools/pocket-export/out/ge2pe/)")
        }
        return file
    }

    private val goldenCleans: List<Pair<String, String>> by lazy {
        val stream = javaClass.getResourceAsStream("/pocket/v2-golden.jsonl")
            ?: throw IllegalStateException("test resource /pocket/v2-golden.jsonl is missing")
        val out = mutableListOf<Pair<String, String>>()
        stream.bufferedReader(Charsets.UTF_8).lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line ->
                val sentences = JSONObject(line).getJSONArray("sentences")
                for (i in 0 until sentences.length()) {
                    val s = sentences.getJSONObject(i)
                    out += s.getString("clean") to s.getString("g2p_raw")
                }
            }
        out
    }

    @Test
    fun byteIdsFollowByt5() {
        // UTF-8 byte b -> id b + 3; 'a' is 0x61.
        assertEquals(listOf(100L), G2p.encodeBytes("a").toList())
        assertEquals(listOf(68L), G2p.encodeBytes("A").toList())
        assertEquals("a", G2p.decodeBytes(listOf(100L)))
        // Multi-byte round trip (U+0633 U+0644 U+0627 U+0645).
        val fa = "سلام"
        val ids = G2p.encodeBytes(fa).toList()
        assertEquals(listOf(219L, 182L, 220L, 135L, 219L, 170L, 220L, 136L), ids)
        assertEquals(fa, G2p.decodeBytes(ids))
        // Specials (pad/eos/unk, sentinels) are skipped on decode.
        assertEquals("a", G2p.decodeBytes(listOf(0L, 1L, 2L, 100L, 259L, 383L)))
        // A truncated multibyte tail is ignored, not an error.
        assertEquals("", G2p.decodeBytes(listOf(219L)))
    }

    @Test
    fun phonemiseMatchesGoldenOnEverySentence() {
        G2p.fromFiles(encoderFile, decoderFile).use { g2p ->
            assertEquals("", g2p.phonemise(""))
            val mismatches = mutableListOf<String>()
            var totalMs = 0L
            for ((clean, want) in goldenCleans) {
                val start = System.nanoTime()
                val got = try {
                    g2p.phonemise(clean)
                } catch (e: IOException) {
                    mismatches += "$clean\n  error: ${e.message}"
                    continue
                }
                totalMs += (System.nanoTime() - start) / 1_000_000
                if (got != want) mismatches += "$clean\n  want: $want\n  got:  $got"
            }
            val count = goldenCleans.size
            println("G2P host timing: $count sentences, ${totalMs} ms total, ${totalMs / count} ms/sentence")
            assertEquals(
                "G2P differs from torch on ${mismatches.size}/$count:\n" + mismatches.joinToString("\n"),
                0,
                mismatches.size,
            )
        }
    }

    @Test
    fun missingModelFailsClearly() {
        try {
            G2p.fromFiles(File("does-not-exist-encoder.onnx"), decoderFile)
            fail("missing encoder must throw")
        } catch (e: IOException) {
            assertTrue(
                "message must name the failure, was: ${e.message}",
                e.message?.startsWith("Couldn't load the Persian pronunciation model: ") == true,
            )
        }
    }
}
