# kotlinx.serialization：保留 @Serializable 生成的序列化器，否则 release 包解析 JSON 会抛 MissingSerializerException
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault, *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    <fields>;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# 数据模型本身按字段名反射/序列化处理，保留属性名
-keep class com.heychat.monitor.data.** { *; }
