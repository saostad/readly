import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.readly"
    // 37: minimum demanded by the pinned geckoview 158 AAR metadata (same as
    // the proven Round 1 prototype; production target/min stay 34/26).
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "app.readly"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += "META-INF/DEPENDENCIES"
        // arm64-only: the pinned GeckoView AAR ships arm64 + armeabi-v7a +
        // x86_64; Readly ships arm64 only (same ndk abiFilter as before).
        jniLibs {
            excludes += "lib/armeabi-v7a/**"
            excludes += "lib/x86/**"
            excludes += "lib/x86_64/**"
        }
    }
}

dependencies {
    // Pinned stable GeckoView (official Mozilla Maven); article engine only.
    implementation("org.mozilla.geckoview:geckoview:158.0.20261007201254")
    implementation(libs.commons.compress)
    implementation(libs.jsoup)
    // readability4j pulls jsoup 1.11.2 and kotlin-stdlib 1.3.72 transitively; we
    // keep our own jsoup 1.23.2 (forced below) and the Kotlin plugin's stdlib.
    // slf4j-api is kept: Readability4J's class init calls LoggerFactory, so
    // excluding it would crash with NoClassDefFoundError (no binding = no-op).
    implementation(libs.readability4j) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    // Pocket TTS needs the ONNX Runtime Java API, but only its Java half: this AAR is
    // onnxruntime-android 1.28.0 with jni/*/libonnxruntime.so stripped out (see
    // stripOnnxRuntimeNative below). One ONNX Runtime ships in the process — sherpa's
    // 1.28.2, whose ABI is API-compatible with the 1.28.0 Java binding — and each
    // libonnxruntime4j_jni.so has its single VERS_1.28.0 version need rewritten to
    // VERS_1.28.2, since no onnxruntime-android 1.28.2 exists on Maven and the 1.28.0
    // JNI lib cannot link against sherpa's runtime otherwise (UnsatisfiedLinkError).
    implementation(files("libs/onnxruntime-android-1.28.0-java-for-ort-1.28.2.aar"))
    // Compile-time resource annotations (@RawRes) for the voice sample clips.
    implementation(libs.annotation)
    testImplementation(libs.junit)
    // Real org.json for JVM unit tests: Android's stub throws on the host JVM.
    testImplementation(libs.json)
    // Host ONNX Runtime for the JVM G2P test (same 1.28.0 Java API as the
    // Android AAR above; the device uses sherpa's native runtime instead).
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.28.0")
}

// readability4j asks for jsoup 1.11.2; force the version we test against.
// GeckoView's POM requests newer kotlin-stdlib / androidx.core than this
// pinned toolchain provides. Both forces below are verified safe against the
// actual geckoview-158 classes (see the Round 1 prototype README for the
// method): kotlin-stdlib 2.4.20 -> 2.2.20 (Gecko is pure Java, zero
// kotlin/* refs) and androidx.core 1.19.0 -> 1.18.0 (all 10 referenced
// members present; newest core fitting AGP 8.13.2).
configurations.all {
    resolutionStrategy {
        force("org.jsoup:jsoup:1.23.2")
        force("org.jetbrains.kotlin:kotlin-stdlib:2.2.20")
        force("androidx.core:core:1.18.0")
    }
}

val sherpaOnnxAarUrl =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar"
val sherpaOnnxAarSha256 =
    "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
val sherpaOnnxAarFile = layout.projectDirectory.file("libs/sherpa-onnx-1.13.8.aar")

tasks.register("fetchSherpaOnnx") {
    description = "Downloads the sherpa-onnx AAR once and verifies its SHA-256 checksum."
    onlyIf("sherpa-onnx AAR already present") { !sherpaOnnxAarFile.asFile.exists() }
    outputs.file(sherpaOnnxAarFile)
    doLast {
        val dest = sherpaOnnxAarFile.asFile
        dest.parentFile.mkdirs()
        try {
            URI(sherpaOnnxAarUrl).toURL().openStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            dest.delete()
            throw GradleException(
                "Could not download sherpa-onnx AAR from $sherpaOnnxAarUrl: " +
                    "${e.javaClass.name}: ${e.message}",
                e,
            )
        }
        val digest = MessageDigest.getInstance("SHA-256")
        dest.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(sherpaOnnxAarSha256, ignoreCase = true)) {
            dest.delete()
            throw GradleException(
                "sherpa-onnx AAR checksum mismatch: expected $sherpaOnnxAarSha256, got $actual",
            )
        }
    }
}

val onnxRuntimeAarUrl =
    "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.28.0/onnxruntime-android-1.28.0.aar"
val onnxRuntimeAarSha256 =
    "f351a0638696f54b35184290dbc001d66daae17281ad0b548d2c70347d53b8a9"
val onnxRuntimeAarFile = layout.projectDirectory.file("libs/onnxruntime-android-1.28.0.aar")
val onnxRuntimePatchedAarFile =
    layout.projectDirectory.file("libs/onnxruntime-android-1.28.0-java-for-ort-1.28.2.aar")

tasks.register("fetchOnnxRuntime") {
    description = "Downloads the onnxruntime-android AAR once and verifies its SHA-256 checksum."
    onlyIf("onnxruntime-android AAR already present") { !onnxRuntimeAarFile.asFile.exists() }
    outputs.file(onnxRuntimeAarFile)
    doLast {
        val dest = onnxRuntimeAarFile.asFile
        dest.parentFile.mkdirs()
        try {
            URI(onnxRuntimeAarUrl).toURL().openStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            dest.delete()
            throw GradleException(
                "Could not download onnxruntime-android AAR from $onnxRuntimeAarUrl: " +
                    "${e.javaClass.name}: ${e.message}",
                e,
            )
        }
        val digest = MessageDigest.getInstance("SHA-256")
        dest.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(onnxRuntimeAarSha256, ignoreCase = true)) {
            dest.delete()
            throw GradleException(
                "onnxruntime-android AAR checksum mismatch: expected $onnxRuntimeAarSha256, got $actual",
            )
        }
    }
}

// The 1.28.0 JNI lib declares its ORT imports at symbol version VERS_1.28.0, but
// the runtime that actually ships is sherpa's 1.28.2, which only provides
// VERS_1.28.2 — ELF symbol versioning then refuses to link (UnsatisfiedLinkError:
// cannot locate symbol "OrtGetApiBase"). The strip task rewrites the single
// VERS_1.28.0 need to VERS_1.28.2 in each JNI lib's .gnu.version_r (same length;
// fix up vna_hash with the ELF hash). Port of
// .agents/handoff/ref/patch_vers.py, plus ELF32 (armeabi-v7a, x86).
val ortJniVersionNeedOld = "VERS_1.28.0"
val ortJniVersionNeedNew = "VERS_1.28.2"

fun elfHash(name: String): Int {
    var h = 0
    for (b in name.toByteArray(Charsets.US_ASCII)) {
        h = (h shl 4) + (b.toInt() and 0xFF)
        val g = h and 0xF0000000.toInt()
        if (g != 0) h = h xor (g ushr 24)
        h = h and g.inv()
    }
    return h
}

fun ByteArray.containsBytes(needle: ByteArray): Boolean {
    if (needle.isEmpty() || size < needle.size) return false
    outer@ for (i in 0..size - needle.size) {
        for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
        return true
    }
    return false
}

fun patchOrtJniVersionNeed(elf: ByteArray, entryName: String): ByteArray {
    fun fail(detail: String): Nothing = throw GradleException(
        "Cannot patch $entryName to $ortJniVersionNeedNew ($detail): " +
            "the onnxruntime-android AAR layout changed, refusing to guess",
    )
    if (ortJniVersionNeedOld.length != ortJniVersionNeedNew.length) {
        fail("version names differ in length")
    }
    if (elf.size < 64 || elf[0] != 0x7F.toByte() || elf[1] != 'E'.code.toByte() ||
        elf[2] != 'L'.code.toByte() || elf[3] != 'F'.code.toByte()
    ) {
        fail("not an ELF file")
    }
    val is64Bit = when (elf[4].toInt()) {
        1 -> false
        2 -> true
        else -> fail("unknown ELF class ${elf[4]}")
    }
    if (elf[5].toInt() != 1) fail("not little-endian")
    val bytes = elf.copyOf()
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun needRange(off: Long, size: Long, what: String) {
        if (off < 0 || size < 0 || off + size > bytes.size) fail("truncated $what")
    }
    // ELF header: section table location (Elf64_Ehdr/Elf32_Ehdr layouts).
    val shOff: Long
    val shEntrySize: Int
    val shCount: Int
    if (is64Bit) {
        needRange(0x28, 8 + 6, "ELF header")
        shOff = buf.getLong(0x28)
        shEntrySize = buf.getShort(0x3A).toInt() and 0xFFFF
        shCount = buf.getShort(0x3C).toInt() and 0xFFFF
    } else {
        needRange(0x20, 4 + 6, "ELF header")
        shOff = buf.getInt(0x20).toLong() and 0xFFFFFFFFL
        shEntrySize = buf.getShort(0x2E).toInt() and 0xFFFF
        shCount = buf.getShort(0x30).toInt() and 0xFFFF
    }
    // Section headers: Elf64_Shdr is 64 bytes (type@4, offset@24, link@40),
    // Elf32_Shdr is 40 bytes (type@4, offset@16, link@24).
    val shSize = if (is64Bit) 64 else 40
    if (shEntrySize < shSize) fail("section header size $shEntrySize < $shSize")
    needRange(shOff, shEntrySize.toLong() * shCount.toLong(), "section table")
    data class Shdr(val type: Int, val offset: Long, val link: Int)
    val sections = (0 until shCount).map { i ->
        val base = (shOff + i.toLong() * shEntrySize).toInt()
        val type = buf.getInt(base + 4)
        val offset = if (is64Bit) buf.getLong(base + 24) else buf.getInt(base + 16).toLong() and 0xFFFFFFFFL
        val link = buf.getInt(base + if (is64Bit) 40 else 24)
        Shdr(type, offset, link)
    }
    val verneed = sections.filter { it.type == 0x6FFFFFFE }
    if (verneed.size != 1) fail("expected one .gnu.version_r section, found ${verneed.size}")
    val vn = verneed[0]
    if (vn.link < 0 || vn.link >= sections.size) fail("bad .gnu.version_r link ${vn.link}")
    val strOff = sections[vn.link].offset
    fun cstr(at: Long): String {
        if (at < 0 || at >= bytes.size) fail("string offset $at out of range")
        var end = at.toInt()
        while (end < bytes.size && bytes[end] != 0.toByte()) end++
        if (end >= bytes.size) fail("unterminated string at $at")
        return String(bytes, at.toInt(), end - at.toInt(), Charsets.US_ASCII)
    }
    // Verneed entries: Elf*_Verneed is 16 bytes (count@2, aux@8, next@12) and each
    // aux entry Elf*_Vernaux is 16 bytes (hash@0, name@8, next@12), same on 32/64-bit.
    var off = vn.offset
    var patched = 0
    var steps = 0
    while (true) {
        if (++steps > 1024) fail("version need chain too long (corrupt?)")
        needRange(off, 16, ".gnu.version_r entry")
        val o = off.toInt()
        val count = buf.getShort(o + 2).toInt() and 0xFFFF
        val aux = buf.getInt(o + 8).toLong() and 0xFFFFFFFFL
        val next = buf.getInt(o + 12).toLong() and 0xFFFFFFFFL
        var a = off + aux
        for (k in 0 until count) {
            needRange(a, 16, ".gnu.version_r aux entry")
            val ai = a.toInt()
            val hash = buf.getInt(ai)
            val nameOff = buf.getInt(ai + 8).toLong() and 0xFFFFFFFFL
            val auxNext = buf.getInt(ai + 12).toLong() and 0xFFFFFFFFL
            if (cstr(strOff + nameOff) == ortJniVersionNeedOld) {
                if (hash != elfHash(ortJniVersionNeedOld)) {
                    fail("hash mismatch for $ortJniVersionNeedOld (AAR layout changed)")
                }
                val at = (strOff + nameOff).toInt()
                ortJniVersionNeedNew.toByteArray(Charsets.US_ASCII).copyInto(bytes, at)
                buf.putInt(ai, elfHash(ortJniVersionNeedNew))
                patched++
            }
            if (auxNext == 0L) break
            a += auxNext
        }
        if (next == 0L) break
        off += next
    }
    if (patched != 1) fail("expected exactly one $ortJniVersionNeedOld need, found $patched")
    return bytes
}

tasks.register("stripOnnxRuntimeNative") {
    description =
        "Copies the onnxruntime-android AAR without its libonnxruntime.so (sherpa already " +
            "ships one) and retargets each libonnxruntime4j_jni.so at sherpa's 1.28.2 runtime."
    dependsOn("fetchOnnxRuntime", "fetchSherpaOnnx")
    inputs.files(onnxRuntimeAarFile, sherpaOnnxAarFile)
    outputs.file(onnxRuntimePatchedAarFile)
    doLast {
        val src = onnxRuntimeAarFile.asFile
        val dest = onnxRuntimePatchedAarFile.asFile
        val sherpa = sherpaOnnxAarFile.asFile
        if (!src.exists()) {
            throw GradleException(
                "onnxruntime-android AAR is missing: ${src.path} " +
                    "(fetchOnnxRuntime should have downloaded it)",
            )
        }
        if (!sherpa.exists()) {
            throw GradleException(
                "sherpa-onnx AAR is missing: ${sherpa.path} " +
                    "(fetchSherpaOnnx should have downloaded it)",
            )
        }
        val sherpaRuntime = mutableMapOf<String, ByteArray>()
        ZipFile(sherpa).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val abi = Regex("jni/([^/]+)/libonnxruntime\\.so")
                    .matchEntire(entry.name)?.groupValues?.get(1)
                if (abi != null && !entry.isDirectory) {
                    sherpaRuntime[abi] = zip.getInputStream(entry).readBytes()
                }
            }
        }
        var stripped = 0
        var patched = 0
        try {
            ZipFile(src).use { zip ->
                dest.outputStream().use { raw ->
                    ZipOutputStream(raw).use { out ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (!entry.isDirectory &&
                                entry.name.matches(Regex("jni/[^/]+/libonnxruntime\\.so"))
                            ) {
                                stripped++
                                continue
                            }
                            val jniAbi = Regex("jni/([^/]+)/libonnxruntime4j_jni\\.so")
                                .matchEntire(entry.name)?.groupValues?.get(1)
                            if (jniAbi != null && !entry.isDirectory) {
                                val runtime = sherpaRuntime[jniAbi]
                                    ?: throw GradleException(
                                        "sherpa-onnx AAR has no jni/$jniAbi/libonnxruntime.so " +
                                            "counterpart for ${entry.name}: refusing to ship an " +
                                            "unchecked JNI lib",
                                    )
                                if (!runtime.containsBytes(ortJniVersionNeedNew.toByteArray(Charsets.US_ASCII))) {
                                    throw GradleException(
                                        "sherpa-onnx AAR's jni/$jniAbi/libonnxruntime.so does not " +
                                            "contain $ortJniVersionNeedNew: sherpa's ONNX Runtime " +
                                            "changed, refusing to guess which version to target",
                                    )
                                }
                                val patchedBytes = patchOrtJniVersionNeed(
                                    zip.getInputStream(entry).readBytes(),
                                    entry.name,
                                )
                                out.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                                out.write(patchedBytes)
                                out.closeEntry()
                                patched++
                                continue
                            }
                            out.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                            if (!entry.isDirectory) {
                                zip.getInputStream(entry).use { it.copyTo(out) }
                            }
                            out.closeEntry()
                        }
                    }
                }
            }
        } catch (e: GradleException) {
            dest.delete()
            throw e
        }
        if (stripped == 0) {
            dest.delete()
            throw GradleException(
                "onnxruntime-android AAR has no jni/*/libonnxruntime.so entries: " +
                    "the AAR layout changed, refusing to guess which native library to strip",
            )
        }
        if (patched == 0) {
            dest.delete()
            throw GradleException(
                "onnxruntime-android AAR has no jni/*/libonnxruntime4j_jni.so entries: " +
                    "the AAR layout changed, refusing to guess which JNI library to patch",
            )
        }
    }
}

tasks.named("preBuild") { dependsOn("fetchSherpaOnnx", "fetchOnnxRuntime", "stripOnnxRuntimeNative") }
