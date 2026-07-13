import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("jacoco")
}

val keystoreProperties = Properties().apply {
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingProperty(name: String, envName: String): String? {
    return (keystoreProperties.getProperty(name) ?: System.getenv(envName))
        ?.takeIf { it.isNotBlank() }
}

val releaseStoreFilePath = signingProperty("storeFile", "TORXONE_RELEASE_STORE_FILE")
val releaseStorePassword = signingProperty("storePassword", "TORXONE_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingProperty("keyAlias", "TORXONE_RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingProperty("keyPassword", "TORXONE_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.torxone.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.torxone.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 22
        versionName = "1.0.18"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
    lint {
        abortOnError = false
    }
    
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Crypto
    implementation("com.goterl:lazysodium-android:5.1.0@aar")
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Google Nearby Connections (P2P offline)
    implementation("com.google.android.gms:play-services-nearby:19.1.0")

    // WebRTC runtime for calls/DataChannel. Kept behind RealtimeEngine so it can be replaced.
    implementation("io.github.webrtc-sdk:android:144.7559.09")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    
    // JSON
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.google.zxing:core:3.5.3")

    // QR scanning
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    // Accompanist (permissions)
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")

    // Tor Embedded
    implementation("info.guardianproject:tor-android:0.4.8.12")
    implementation("info.guardianproject:jtorctl:0.4.5.7")

    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.6.0")

    // DataStore for Settings
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    
    // ----------------------------------------------------
    // Testing Dependencies
    // ----------------------------------------------------
    // Unit Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.10.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("app.cash.turbine:turbine:1.0.0")
    testImplementation("org.robolectric:robolectric:4.11.1")
    
    // Instrumentation Testing
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.navigation:navigation-testing:2.7.7")
    androidTestImplementation("org.mockito:mockito-android:5.10.0")
    androidTestImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    
    // Room Testing
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}

kapt {
    correctErrorTypes = true
}
