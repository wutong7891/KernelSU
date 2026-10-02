# Protobuf
-shrinkunusedprotofields

# Commons-compress
-dontwarn com.github.luben.zstd.**

# ML Kit discovers bundled OCR components through registrars/reflection. Keep the
# implementation intact because aggressive release shrinking can leave the
# recognizer delegate uninitialized on some OEM runtimes.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
