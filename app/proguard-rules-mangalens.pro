# Keep rules for the fork's translation feature.
#
# Deliberately a separate file from proguard-rules.pro: both this fork and
# upstream append to the end of that one, which is the single most
# conflict-prone position in it. Nothing here has an upstream counterpart, so
# nothing here needs to be merged, ever.
#
# Wired in via proguardFiles() in app/build.gradle.kts.

# ML Kit text recognition (bundled models, fork's translate feature).
# Components are instantiated reflectively via MlKitComponentDiscoveryService
# metadata; R8 full mode strips them, breaking TextRecognition.getClient with
# an internal NPE in release builds.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_bundled_common.** { *; }
-dontwarn com.google.mlkit.**

# ONNX Runtime (PaddleOCR recognition, fork's translate feature).
# Its JNI builds the result types — TensorInfo, OnnxTensor, OrtSession$Result —
# with NewObject, so no Java code ever references those constructors and R8
# removes them while keeping the class. The debug build is fine and the release
# build aborts the whole process the first time a line is recognized:
#
#   JNI DETECTED ERROR IN APPLICATION: mid == null
#       in call to NewObject
#       from ai.onnxruntime.OrtSession.run(...)
#
# The AAR ships no consumer rules, so this has to live here. Do not narrow it to
# the classes named above: the JNI reaches for more of them as soon as a model
# returns anything other than a single float tensor.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
