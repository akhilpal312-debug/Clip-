# JNI entry points referenced only from native code / reflection.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.aiclipcreator.core.ai.WhisperNative { *; }
-keep class com.aiclipcreator.core.ai.LlmNative { *; }

# Room / Compose / OkHttp standard keep rules are provided by their libraries.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn ai.onnxruntime.**
-dontwarn org.tensorflow.lite.**
-keep class ai.onnxruntime.** { *; }
-keep class org.tensorflow.lite.** { *; }
