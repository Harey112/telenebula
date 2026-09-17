# kotlinx.serialization: keep serializers for the models (generated companions are looked up reflectively only
# for polymorphic/sealed hierarchies; explicit keep is the documented safe default).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.telenebula.**$$serializer { *; }
-keepclassmembers class com.telenebula.** { *** Companion; }
-keepclasseswithmembers class com.telenebula.** { kotlinx.serialization.KSerializer serializer(...); }

# ML Kit barcode scanning (QR): its ComponentDiscovery mechanism reflectively instantiates each
# module's ComponentRegistrar via a public no-arg constructor found via manifest metadata. None of
# barcode-scanning's/common's/play-services' own shipped consumer rules keep that constructor, so
# R8 removes it and getClient() throws deep inside Play Services — release/minified builds only,
# never an unminified debug build. This is the official pattern for that failure mode.
-keep class * implements com.google.firebase.components.ComponentRegistrar { public <init>(); }
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-keep class com.google.android.gms.common.internal.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**
