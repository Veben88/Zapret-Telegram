# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class ru.partitrainer.**$$serializer { *; }
-keepclassmembers class ru.partitrainer.core.** { *** Companion; }
# XmlPullParser живёт в платформе Android
-dontwarn org.xmlpull.v1.**
