# Keep Kotlinx Serialization & OkHttp rules
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 仅针对 com.moji.ai.** 下带 @Serializable 注解的类（兼容顶层类、嵌套 DTO 与枚举）保留序列化所需成员
-if @kotlinx.serialization.Serializable class com.moji.ai.**
-keepclassmembers class com.moji.ai.<1> {
    *** Companion;
}

-if @kotlinx.serialization.Serializable class com.moji.ai.** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class com.moji.ai.**
-keepclassmembers class com.moji.ai.<1>$$serializer {
    public static final ** INSTANCE;
    kotlinx.serialization.descriptors.SerialDescriptor getDescriptor();
}

-keepclassmembers @kotlinx.serialization.Serializable enum com.moji.ai.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
