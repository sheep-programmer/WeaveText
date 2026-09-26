# JNI 入口与回调不能被混淆。 Keep JNI entry points and callbacks.
-keep class com.weavetext.ime.core.NativeEngine { *; }
-keep class com.weavetext.ime.voice.NativePluginHost { *; }
-keep interface com.weavetext.ime.voice.NativeSpeechCallback { *; }
-keep class * implements com.weavetext.ime.voice.NativeSpeechCallback { *; }
