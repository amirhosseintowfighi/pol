# kotlinx.serialization: keep generated serializers of the shared core.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class studio.virgule.pol.core.**$$serializer { *; }
-keepclassmembers class studio.virgule.pol.core.** { *** Companion; }
-keepclasseswithmembers class studio.virgule.pol.core.** { kotlinx.serialization.KSerializer serializer(...); }
# Strip verbose logging from release builds (the app never logs message content, but be safe).
-assumenosideeffects class android.util.Log { public static int v(...); public static int d(...); public static int i(...); }
