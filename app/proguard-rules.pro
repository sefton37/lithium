# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Hilt
-keepclasseswithmembernames class * {
    @dagger.hilt.* <methods>;
}

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface *
-keepclassmembers @androidx.room.Dao interface * { *; }

# SQLCipher
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }

# llama.cpp JNI bridge — native method declarations must survive proguard
-keep class ai.talkingrock.lithium.ai.LlamaCpp { *; }

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class ai.talkingrock.lithium.**$$serializer { *; }
-keepclassmembers class ai.talkingrock.lithium.** {
    *** Companion;
}
-keepclasseswithmembers class ai.talkingrock.lithium.** {
    kotlinx.serialization.KSerializer serializer(...);
}
