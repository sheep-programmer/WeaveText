# JNI 入口与回调不能被混淆。 Keep JNI entry points and callbacks.
-keep class com.weavetext.ime.core.NativeEngine { *; }
-keep class com.weavetext.ime.link.NativeLink { *; }
-keep class com.weavetext.ime.voice.NativePluginHost { *; }
-keep interface com.weavetext.ime.voice.NativeSpeechCallback { *; }
-keep class * implements com.weavetext.ime.voice.NativeSpeechCallback { *; }

# sherpa-onnx 的 JNI 按原名读取整个配置对象图及识别结果，不能只保留 native 方法。
# Its JNI looks up config/result classes and fields by name, including nested model configs.
-keep class com.k2fsa.sherpa.onnx.** { *; }
