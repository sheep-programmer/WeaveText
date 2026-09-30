package com.weavetext.ime.voicesmoke;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.res.AssetManager;
import android.os.Bundle;

import java.io.File;
import java.io.RandomAccessFile;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/** Uses only the installed app's classes: test dependencies cannot hide R8/JNI failures. */
public final class VoiceSmoke extends Instrumentation {
    private static final String SHERPA = "com.k2fsa.sherpa.onnx.";
    private Bundle args;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); args = arguments; start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String kind = args.getString("case", "online-bundled");
            File root = getTargetContext().getExternalFilesDir("voice-smoke");
            if (root == null || (!root.isDirectory() && !root.mkdirs())) throw new IllegalStateException("No test directory");
            String text;
            if (kind.equals("prepare")) text = root.getPath();
            else if (args.getString("backend", "bundled").equals("native")) text = nativeRecognize(kind);
            else if (kind.startsWith("online")) text = online(kind.equals("online-bundled"));
            else if (kind.equals("offline")) text = offline();
            else if (kind.equals("punctuation")) text = punctuation();
            else throw new IllegalArgumentException("Unknown case: " + kind);
            if (!kind.equals("prepare") && text.trim().isEmpty()) throw new AssertionError("Recognition returned no text");
            String expected = args.getString("expect", "");
            if (!text.contains(expected)) throw new AssertionError("Expected " + expected + " in " + text);
            result.putString("stream", "PASS " + kind + ": " + text + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            while (failure instanceof InvocationTargetException && failure.getCause() != null) failure = failure.getCause();
            StringWriter out = new StringWriter();
            failure.printStackTrace(new PrintWriter(out));
            result.putString("stream", "FAIL: " + out + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private Class<?> target(String name) throws ClassNotFoundException {
        return Class.forName(name, true, getTargetContext().getClassLoader());
    }

    private Object config(String name) throws Exception { return target(SHERPA + name).getConstructor().newInstance(); }

    private Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private Object call(Object object, String name, Class<?>[] types, Object... values) throws Exception {
        return object.getClass().getMethod(name, types).invoke(object, values);
    }

    private Object call(Object object, String name) throws Exception { return call(object, name, new Class<?>[0]); }

    private Object recognizer(String name, Object config, boolean assets) throws Exception {
        return target(SHERPA + name).getConstructor(AssetManager.class, config.getClass())
                .newInstance(assets ? getTargetContext().getAssets() : null, config);
    }

    private String online(boolean bundled) throws Exception {
        // Check the exact field from the reported failure before calling native code.
        target(SHERPA + "OnlineRecognizerConfig").getDeclaredField("decodingMethod");
        Object config = config("OnlineRecognizerConfig");
        Object model = field(config, "modelConfig");
        String dir = bundled ? "models/asr-stream-small" : args.getString("model");
        set(field(model, "zipformer2Ctc"), "model", dir + "/model.int8.onnx");
        set(model, "tokens", dir + "/tokens.txt");
        set(model, "numThreads", 2);
        Object rec = recognizer("OnlineRecognizer", config, bundled);
        Object stream = null;
        try {
            stream = call(rec, "createStream", new Class<?>[]{String.class}, "");
            float[] samples = wav(args.getString("wav"));
            for (int i = 0; i < samples.length; i += 3200) {
                call(stream, "acceptWaveform", new Class<?>[]{float[].class, int.class},
                        Arrays.copyOfRange(samples, i, Math.min(i + 3200, samples.length)), 16000);
                decodeOnline(rec, stream);
            }
            call(stream, "acceptWaveform", new Class<?>[]{float[].class, int.class}, new float[8000], 16000);
            decodeOnline(rec, stream);
            return (String) call(call(rec, "getResult", new Class<?>[]{stream.getClass()}, stream), "getText");
        } finally {
            if (stream != null) call(stream, "release");
            call(rec, "release");
        }
    }

    private void decodeOnline(Object rec, Object stream) throws Exception {
        Class<?>[] types = {stream.getClass()};
        while ((Boolean) call(rec, "isReady", types, stream)) call(rec, "decode", types, stream);
    }

    private String offline() throws Exception {
        target(SHERPA + "OfflineRecognizerConfig").getDeclaredField("decodingMethod");
        Object config = config("OfflineRecognizerConfig");
        Object model = field(config, "modelConfig");
        String arch = args.getString("arch", "zipformer-ctc");
        String name = switch (arch) {
            case "zipformer-ctc" -> "zipformerCtc";
            case "sense-voice" -> "senseVoice";
            case "paraformer" -> "paraformer";
            default -> throw new IllegalArgumentException(arch);
        };
        set(field(model, name), "model", args.getString("model") + "/model.int8.onnx");
        if (arch.equals("sense-voice")) {
            set(field(model, name), "language", "auto");
            set(field(model, name), "useInverseTextNormalization", true);
        }
        set(model, "tokens", args.getString("model") + "/tokens.txt");
        set(model, "numThreads", 2);
        Object rec = recognizer("OfflineRecognizer", config, false);
        Object stream = null;
        try {
            stream = call(rec, "createStream");
            call(stream, "acceptWaveform", new Class<?>[]{float[].class, int.class}, wav(args.getString("wav")), 16000);
            call(rec, "decode", new Class<?>[]{stream.getClass()}, stream);
            return (String) call(call(rec, "getResult", new Class<?>[]{stream.getClass()}, stream), "getText");
        } finally {
            if (stream != null) call(stream, "release");
            call(rec, "release");
        }
    }

    private String punctuation() throws Exception {
        Object model = config("OfflinePunctuationModelConfig");
        set(model, "ctTransformer", args.getString("model") + "/model.int8.onnx");
        Object config = target(SHERPA + "OfflinePunctuationConfig").getConstructor(model.getClass()).newInstance(model);
        Object rec = recognizer("OfflinePunctuation", config, false);
        try {
            return (String) call(rec, "addPunctuation", new Class<?>[]{String.class}, "你好今天下午三点开会");
        } finally { call(rec, "release"); }
    }

    private String nativeRecognize(String kind) throws Exception {
        Class<?> bridge = target("com.weavetext.ime.voice.local.NativeAsr");
        // Downloaded runtime libraries normally live in private storage, with read-only permissions.
        File runtime = new File(getTargetContext().getFilesDir(), "voice-smoke-runtime");
        Files.createDirectories(runtime.toPath());
        for (String name : new String[]{"libonnxruntime.so", "libsherpa-onnx-c-api.so"}) {
            File dest = new File(runtime, name);
            if (dest.exists() && !dest.setWritable(true, true)) throw new IllegalStateException("Cannot replace " + dest);
            Files.copy(new File(args.getString("runtime"), name).toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (!dest.setReadOnly()) throw new IllegalStateException("Cannot protect " + dest);
        }
        Object error = bridge.getMethod("nativeLoad", String.class).invoke(null, runtime.getPath());
        if (error != null) throw new IllegalStateException(error.toString());
        String dir = args.getString("model");
        boolean online = kind.startsWith("online");
        boolean punct = kind.equals("punctuation");
        String prefix = punct ? "nativePunct" : online ? "nativeOnline" : "nativeOffline";
        long handle;
        if (punct) handle = (Long) bridge.getMethod(prefix + "Create", String.class, int.class).invoke(null, dir + "/model.int8.onnx", 1);
        else if (online) handle = (Long) bridge.getMethod(prefix + "Create", String.class, String.class, String.class, int.class, float.class, float.class, float.class)
                .invoke(null, "zipformer2-ctc", dir + "/model.int8.onnx", dir + "/tokens.txt", 2, 2.4f, 0.8f, 20f);
        else handle = (Long) bridge.getMethod(prefix + "Create", String.class, String.class, String.class, int.class)
                .invoke(null, args.getString("arch", "zipformer-ctc"), dir + "/model.int8.onnx", dir + "/tokens.txt", 2);
        if (handle == 0) throw new IllegalStateException("Create failed: " + bridge.getMethod("nativeLastError").invoke(null));
        try {
            if (punct) return (String) bridge.getMethod("nativePunctuate", long.class, String.class).invoke(null, handle, "你好今天下午三点开会");
            float[] samples = wav(args.getString("wav"));
            if (!online) return (String) bridge.getMethod(prefix + "Decode", long.class, float[].class, int.class).invoke(null, handle, samples, samples.length);
            Method accept = bridge.getMethod(prefix + "Accept", long.class, float[].class, int.class);
            for (int i = 0; i < samples.length; i += 3200) {
                float[] chunk = Arrays.copyOfRange(samples, i, Math.min(i + 3200, samples.length));
                accept.invoke(null, handle, chunk, chunk.length);
            }
            bridge.getMethod(prefix + "Finish", long.class).invoke(null, handle);
            return (String) bridge.getMethod(prefix + "Text", long.class).invoke(null, handle);
        } finally { bridge.getMethod(prefix + "Destroy", long.class).invoke(null, handle); }
    }

    private static float[] wav(String path) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(path, "r")) {
            if (file.readInt() != 0x52494646) throw new IllegalArgumentException("Not RIFF");
            file.skipBytes(4);
            if (file.readInt() != 0x57415645) throw new IllegalArgumentException("Not WAVE");
            boolean format = false;
            while (file.getFilePointer() + 8 <= file.length()) {
                int tag = file.readInt();
                int size = Integer.reverseBytes(file.readInt());
                long next = file.getFilePointer() + size + (size & 1);
                if (tag == 0x666d7420) {
                    int pcm = Short.reverseBytes(file.readShort()) & 0xffff;
                    int channels = Short.reverseBytes(file.readShort()) & 0xffff;
                    int rate = Integer.reverseBytes(file.readInt());
                    file.skipBytes(6);
                    int bits = Short.reverseBytes(file.readShort()) & 0xffff;
                    if (pcm != 1 || channels != 1 || rate != 16000 || bits != 16) throw new IllegalArgumentException("Need mono 16 kHz PCM16 WAV");
                    format = true;
                } else if (tag == 0x64617461) {
                    if (!format) throw new IllegalArgumentException("No WAV format");
                    float[] samples = new float[size / 2];
                    for (int i = 0; i < samples.length; i++) samples[i] = Short.reverseBytes(file.readShort()) / 32768f;
                    return samples;
                }
                file.seek(next);
            }
            throw new IllegalArgumentException("No WAV data");
        }
    }
}
