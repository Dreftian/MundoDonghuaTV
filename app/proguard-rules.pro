# ProGuard / R8 Rules for Donghua TV

# Preserve all data models, reflection targets, and extractor models
-keep class com.donghua.tv.data.** { *; }
-keepclassmembers class com.donghua.tv.data.** { *; }

# Preserve player classes, Javascript interfaces, and callbacks
-keep class com.donghua.tv.player.** { *; }
-keepclassmembers class com.donghua.tv.player.** { *; }

# Preserve UI and MainActivity
-keep class com.donghua.tv.ui.** { *; }
-keep class com.donghua.tv.** { *; }

# Keep annotations, signatures, and reflection metadata
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-keep class kotlin.Metadata { *; }

# Keep AndroidX Keep annotations
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <methods>;
}

# Keep OkHttp & Jsoup
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# Keep Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
