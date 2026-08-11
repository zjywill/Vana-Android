# kotlinx.serialization 的 @Serializable 类靠反射找 serializer,别把它们的名字混淆掉。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.pinapia.vana.**$$serializer { *; }
-keepclassmembers class com.pinapia.vana.** {
    *** Companion;
}
-keepclasseswithmembers class com.pinapia.vana.** {
    kotlinx.serialization.KSerializer serializer(...);
}
