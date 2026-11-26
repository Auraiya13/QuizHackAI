# Quiz Hack AI ProGuard Rules

# Keep models
-keep class com.quizhack.ai.model.** { *; }

# Keep Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Keep ML Kit
-keep class com.google.mlkit.** { *; }

# Keep accessibility service
-keep class com.quizhack.ai.service.QuizAccessibilityService { *; }
