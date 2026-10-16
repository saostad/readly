import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.readly"
    compileSdk = 34
    // Pinned: the host SDK ships only build-tools 36.1.0 (AGP 8.13 defaults to
    // 35.0.0, which would need an SDK install outside the repo).
    buildToolsVersion = "36.1.0"

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
}

dependencies {
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
    testImplementation(libs.junit)
}

// readability4j asks for jsoup 1.11.2; force the version we test against.
configurations.all {
    resolutionStrategy {
        force("org.jsoup:jsoup:1.23.2")
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

tasks.named("preBuild") { dependsOn("fetchSherpaOnnx") }
