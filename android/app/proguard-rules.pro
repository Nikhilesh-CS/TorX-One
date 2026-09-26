# TorX One ProGuard Rules
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Nearby
-keep class com.google.android.gms.nearby.** { *; }

# SQLCipher
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**
-keep class net.zetetic.** { *; }
-dontwarn net.zetetic.**

# WebRTC (JNI native bindings and reflection)
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# BouncyCastle (cryptographic providers and reflection)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ZXing (QR scanning)
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
