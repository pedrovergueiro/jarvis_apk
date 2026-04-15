# Jarvis ProGuard Rules

# Manter classes de modelo
-keep class com.jarvis.assistant.memory.** { *; }
-keep class com.jarvis.assistant.ai.** { *; }

# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Retrofit
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions

# Gson
-keep class com.google.gson.** { *; }
-keepattributes *Annotation*

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Porcupine
-keep class ai.picovoice.** { *; }
