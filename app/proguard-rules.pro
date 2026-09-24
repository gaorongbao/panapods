# PanaPods ProGuard Rules

# Xposed API (traditional + modern)
-keep class com.panapods.xposed.** { *; }
-dontwarn com.panapods.xposed.**
-dontwarn io.github.libxposed.**

# Keep Xposed entry point (modern API: XposedModule no-arg constructor)
-keep class com.panapods.hook.HookEntry {
    public <init>();
}

# Keep Xposed entry point and all hook classes
-keep class com.panapods.hook.** { *; }
-keep class com.panapods.bridge.PanaBridge { *; }

# Keep serialized data classes
-keepclassmembers class * implements kotlinx.serialization.KSerializable {
    <fields>;
}
-keep class com.panapods.headphones.HeadphoneState { *; }

# Keep Airoha protocol constants
-keep class com.panapods.protocol.RaceIdPana { *; }
-keep class com.panapods.protocol.RaceId { *; }

# Xposed API
-dontwarn io.github.libxposed.**
-dontwarn com.panapods.xposed.**

# Compose
-dontwarn androidx.compose.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.panapods.**$$serializer { *; }
-keepclassmembers class com.panapods.** { *** Companion; }
-keepclasseswithmembers class com.panapods.** { kotlinx.serialization.KSerializer serializer(...); }
