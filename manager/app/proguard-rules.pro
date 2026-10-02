# Protobuf
-shrinkunusedprotofields

# Commons-compress
-dontwarn com.github.luben.zstd.**

# ML Kit discovers bundled OCR components through registrars/reflection. Keep the
# implementation intact because aggressive release shrinking can leave the
# recognizer delegate uninitialized on some OEM runtimes.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_bundled_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
