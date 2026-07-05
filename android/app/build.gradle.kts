plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.astramesh.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.astramesh.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.0.4"
    }

    signingConfigs {
        create("release") {
            storeFile = file("astramesh.jks")
            storePassword = "astramesh123"
            keyAlias = "astramesh"
            keyPassword = "astramesh123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Crypto
    implementation("com.goterl:lazysodium-android:5.1.0@aar")
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Google Nearby Connections (P2P offline)
    implementation("com.google.android.gms:play-services-nearby:19.1.0")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    
    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // Accompanist (permissions)
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")

    // Tor Embedded
    implementation("info.guardianproject:tor-android:0.4.8.12")
    implementation("info.guardianproject:jtorctl:0.4.5.7")

    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.6.0")

    // DataStore for Settings
    implementation("androidx.datastore:datastore-preferences:1.0.0")
}
