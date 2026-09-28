# TorX One ProGuard Rules
# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Nearby
-keep class com.google.android.gms.nearby.** { *; }

# SQLCipher
-keep class net.zetetic.** { *; }
-dontwarn net.zetetic.**

# WebRTC (JNI native bindings and reflection)
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# BouncyCastle warnings from optional algorithms not bundled by the provider.
-dontwarn org.bouncycastle.**

# ZXing (QR scanning)
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
