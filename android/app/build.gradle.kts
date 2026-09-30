import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

fun buildConfigString(name: String): String {
    val value = providers.gradleProperty(name)
        .orElse(providers.environmentVariable(name))
        .orElse("")
        .get()
    return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

android {
    namespace = "com.torxone.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.torxone.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TORX_STUN_URLS", buildConfigString("TORX_STUN_URLS"))
        buildConfigField("String", "TORX_TURN_URLS", buildConfigString("TORX_TURN_URLS"))
        buildConfigField("String", "TORX_TURN_USERNAME", buildConfigString("TORX_TURN_USERNAME"))
        buildConfigField("String", "TORX_TURN_CREDENTIAL", buildConfigString("TORX_TURN_CREDENTIAL"))
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    packaging {
        resources {
            pickFirsts += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    sourceSets {
        getByName("test").assets.directories.add("$projectDir/schemas")
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.maxHeapSize = "2048m"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // ── Compose BOM ──
    val composeBom = platform("androidx.compose:compose-bom:2026.03.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // ── AndroidX core ──
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.navigation:navigation-compose:2.9.8")

    // ── Room (encrypted DB via SQLCipher) ──
    val roomVersion = "2.8.5"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    implementation("net.zetetic:sqlcipher-android:4.10.0@aar")
    implementation("androidx.sqlite:sqlite:2.7.1")

    // ── Security / Crypto ──
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")

    // ── Nearby Connections ──
    implementation("com.google.android.gms:play-services-nearby:19.3.0")

    // ── Embedded Tor runtime ──
    implementation(files("libs/tor-android-0.4.8.12.aar"))
    implementation("info.guardianproject:jtorctl:0.4.5.7")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // ── Coroutines ──
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // ── Serialization ──
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // ── CameraX (QR scanning) ──
    val cameraxVersion = "1.6.0"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ── ZXing (QR gen/scan) ──
    implementation("com.google.zxing:core:3.5.3")

    // ── Biometric ──
    implementation("androidx.biometric:biometric:1.1.0")

    // ── DataStore ──
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // ── WebRTC (media engine for voice/video calls) ──
    implementation("io.getstream:stream-webrtc-android:1.3.10")

    // ── Testing ──
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("app.cash.turbine:turbine:1.2.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

// Resolve the actual release graph, including the separately vendored native Tor binary.
tasks.register("securityInventory") {
    val inventoryDir = layout.buildDirectory.dir("reports/security")
    outputs.dir(inventoryDir)
    outputs.upToDateWhen { false }
    doLast {
        val destination = inventoryDir.get().asFile.apply { mkdirs() }
        val modules = configurations.getByName("releaseRuntimeClasspath")
            .resolvedConfiguration.resolvedArtifacts.map { it.moduleVersion.id }
            .distinctBy { "${it.group}:${it.name}:${it.version}" }
            .sortedBy { "${it.group}:${it.name}:${it.version}" }
        fun escaped(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
        val components = modules.map { id ->
            val purl = "pkg:maven/${id.group}/${id.name}@${id.version}"
            """{"type":"library","group":"${escaped(id.group)}","name":"${escaped(id.name)}","version":"${escaped(id.version)}","purl":"${escaped(purl)}"}"""
        }.toMutableList()
        val tor = file("libs/tor-android-0.4.8.12.aar")
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(tor.readBytes()).joinToString("") { "%02x".format(it) }
        components += """{"type":"library","name":"tor-android","version":"0.4.8.12","hashes":[{"alg":"SHA-256","content":"$hash"}]}"""
        components += """{"type":"library","name":"tor","version":"0.4.8.12","purl":"pkg:generic/tor@0.4.8.12"}"""
        destination.resolve("sbom.cdx.json").writeText(
            """{"bomFormat":"CycloneDX","specVersion":"1.5","version":1,"components":[${components.joinToString(",")}]}"""
        )
        destination.resolve("dependencies.txt").writeText(
            modules.joinToString("\n") { "${it.group}:${it.name}:${it.version}" } +
                "\ntor-android:0.4.8.12 SHA-256=$hash\n"
        )
    }
}
