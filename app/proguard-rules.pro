# Keep Kotlinx Serialization & OkHttp rules
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.moji.ai.**$$serializer { *; }
-keepclassmembers class com.moji.ai.** {
    *** Companion;
}
-keepclasseswithmembers class com.moji.ai.** {
    kotlinx.serialization.KSerializer serializer(...);
}
