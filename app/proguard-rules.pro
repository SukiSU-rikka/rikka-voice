# VOICEVOX CORE / sherpa-onnx / piper-plus 的原生库会按“字段名”反射读取 Java/Kotlin 对象
# （例如 Synthesizer.Builder.cpuNumThreads、OfflineTtsConfig 的各个字段）。
# 一旦被 R8 改名或删掉，就会出现 NoSuchFieldError: cpuNumThreads 之类的错误。
-keep class jp.hiroshiba.voicevoxcore.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.rikka.voice.engines.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn jp.hiroshiba.voicevoxcore.**
-dontwarn com.k2fsa.sherpa.onnx.**
