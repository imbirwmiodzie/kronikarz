import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "pl.kronikarz.poc"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.kronikarz.poc"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-poc"

        ndk {
            // arm64 = typowe telefony, armeabi-v7a = starsze, x86_64 = emulator.
            // Mniejszy APK na jeden telefon: ./gradlew assembleDebug -Pabis=arm64-v8a
            val abis = (project.findProperty("abis") as String?)?.split(",")?.map { it.trim() }
                ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            abiFilters += abis
        }
    }

    signingConfigs {
        // Stały klucz POC (w repo, hasło jawne — NIE do sklepu). Dzięki niemu APK z CI i z każdego
        // komputera ma ten sam podpis i da się go instalować na poprzednią wersję bez odinstalowania.
        create("poc") {
            storeFile = file("poc-signing.keystore")
            storePassword = "android"
            keyAlias = "kronikarz-poc"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("poc")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("poc")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // model ONNX ładujemy bez kompresji (szybszy odczyt)
        noCompress += listOf("onnx")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            // Narzędzie strojenia: ./gradlew testDebugUnitTest --tests '*ClusteringEvalTool*' -PevalFaces=tools/out/x/faces.json
            it.systemProperty("evalFaces", project.findProperty("evalFaces") ?: "")
            it.systemProperty("evalLabels", project.findProperty("evalLabels") ?: "")
            it.systemProperty("evalOut", project.findProperty("evalOut") ?: "")
            it.systemProperty("evalThreshold", project.findProperty("evalThreshold") ?: "")
            it.systemProperty("evalVerbose", project.findProperty("evalVerbose") ?: "")
            it.testLogging.showStandardStreams = project.hasProperty("evalFaces")
        }
    }
    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.navigation:navigation-compose:2.9.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // Detekcja twarzy: wariant z modelem w paczce aplikacji (bez pobierania przez Google Play Services / sieć)
    implementation("com.google.mlkit:face-detection:16.1.7")
    // Embeddingi twarzy: SFace (MobileFaceNet, Apache 2.0) w formacie ONNX
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}

// ---------------------------------------------------------------------------------------------
// Model rozpoznawania twarzy SFace (OpenCV Zoo, Apache 2.0) — pobierany przy budowaniu,
// nie trzymamy 38 MB binarki w repozytorium. Aplikacja NIE łączy się z siecią, robi to tylko Gradle.
// ---------------------------------------------------------------------------------------------
val sfaceUrl = "https://huggingface.co/opencv/face_recognition_sface/resolve/main/face_recognition_sface_2021dec.onnx"
val sfaceSha256 = "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79"
val sfaceFile = layout.projectDirectory.file("src/main/assets/models/sface.onnx").asFile

fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { md ->
    file.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    md.digest().joinToString("") { "%02x".format(it) }
}

val downloadFaceModel by tasks.registering {
    description = "Pobiera model SFace (ONNX) do assets, jeśli go brakuje, i sprawdza SHA-256."
    outputs.file(sfaceFile)
    doLast {
        if (sfaceFile.exists() && sha256(sfaceFile) == sfaceSha256) return@doLast
        sfaceFile.parentFile.mkdirs()
        logger.lifecycle("Pobieram model SFace z $sfaceUrl …")
        val tmp = File(sfaceFile.parentFile, "sface.onnx.part")
        URI(sfaceUrl).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        val actual = sha256(tmp)
        if (actual != sfaceSha256) {
            tmp.delete()
            throw GradleException("Zła suma SHA-256 modelu SFace: $actual (oczekiwano $sfaceSha256)")
        }
        tmp.renameTo(sfaceFile)
    }
}
tasks.named("preBuild") { dependsOn(downloadFaceModel) }
