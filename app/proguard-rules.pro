# sherpa-onnx looks its Kotlin API classes up by name over JNI: keep them all.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# sherpa-onnx looks up invoke([F)Ljava/lang/Integer; on the synthesis callback by name: keep it.
-keepclassmembers class * implements kotlin.jvm.functions.Function1 { java.lang.Integer invoke(float[]); }
