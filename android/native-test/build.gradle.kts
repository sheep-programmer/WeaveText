import java.net.URI
import java.security.MessageDigest

// 在桌面 JVM 上测试真实的 JNI 绑定：编译 app 中与 Android 无关的 JNI 封装类，
// 加载本机构建的 libweave（cargo build -p weave-ffi --release）。
// Tests the real JNI bindings on the desktop JVM: compiles the Android-free JNI wrappers from
// :app and loads the host build of libweave.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

val coreDir = rootProject.projectDir.resolve("../core")
val appSrc = rootProject.projectDir.resolve("app/src/main/java")
val cargo = "${System.getProperty("user.home")}/.cargo/bin/cargo"

kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.srcDir(appSrc)
}

sourceSets["main"].kotlin {
    include(
        "com/weavetext/ime/core/NativeEngine.kt",
        "com/weavetext/ime/voice/NativePluginHost.kt",
        // 与 Android 无关的模型/语音逻辑。 Android-free model and speech logic.
        "com/weavetext/ime/models/NativeArchive.kt",
        "com/weavetext/ime/models/ModelCatalog.kt",
        "com/weavetext/ime/models/Downloader.kt",
        "com/weavetext/ime/models/ModelFetcher.kt",
        "com/weavetext/ime/voice/local/TwoPassRecognizer.kt",
        // 按需下载的运行库的 JNI 绑定与识别器。 JNI bindings and recognizers for the downloadable runtime.
        "com/weavetext/ime/voice/local/NativeAsr.kt",
        "com/weavetext/ime/voice/local/NativeAsrModels.kt",
    )
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

val buildHostLib by tasks.registering(Exec::class) {
    workingDir = coreDir
    commandLine(cargo, "build", "-q", "-p", "weave-ffi", "--release")
}

val buildDicts by tasks.registering(Exec::class) {
    commandLine(rootProject.projectDir.resolve("../data/build.sh").absolutePath)
}

tasks.test {
    dependsOn(buildHostLib, buildDicts)
    systemProperty("java.library.path", coreDir.resolve("target/release").absolutePath)
    systemProperty("weave.data", rootProject.projectDir.resolve("../data/build").absolutePath)
    // 可选：真实插件目录与 16k wav（不在仓库中）。 Optional real plugins + wav (outside the repo).
    System.getenv("WEAVE_PLUGIN_DIR")?.let { systemProperty("weave.pluginDir", it) }
    System.getenv("WEAVE_TEST_WAV")?.let { systemProperty("weave.wav", it) }
    systemProperty("weave.models", project(":app").layout.buildDirectory.dir("modelAssets/models").get().asFile.absolutePath)
    systemProperty("weave.cache", refCache.absolutePath)
    systemProperty("weave.asrRuntime", desktopRuntimeDir.resolve("lib").absolutePath)
    systemProperty("weave.testWavs", rootProject.projectDir.resolve("../.ref/sherpa/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01/test_wavs").absolutePath)
    dependsOn(fetchDesktopRuntime)
    dependsOn(":app:fetchBuiltinModels")
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

val refCache = rootProject.projectDir.resolve("../.ref/cache")

// 桌面版 sherpa-onnx（Java API + 本机原生库），只用于测试两遍识别流程；按系统选择并校验 SHA-256。
// Desktop sherpa-onnx (Java API + host natives), test-only; picked per OS and SHA-256 verified.
val sherpaDesktop = "1.13.8"
val hostNative: Pair<String, String> = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    when {
        os.contains("mac") && arm -> "osx-aarch64" to "42e272180c8836127f024f3335d7afcdfb30fe0164b78330d5d449034e34ce34"
        os.contains("mac") -> "osx-x64" to "9190c28951d85efdbd376bae6b6dff12993311ad605945289e8459bf9c886a96"
        arm -> "linux-aarch64" to "5123d2e48ae1a7ce82ba89ce153906c651bf418a63db2f7dff82265e6d49c104"
        else -> "linux-x64" to "30c93b59381113f9c20aedbbf9fc1ad399158f6bc03dddc0f8934a6e28e069ba"
    }
}
val sherpaJars = listOf(
    "sherpa-onnx-jvm-$sherpaDesktop.jar" to "77b7b047fade4eadada96b568eb92615049aaf1dc317c7244e46c1ea38b9a63b",
    "sherpa-onnx-native-lib-${hostNative.first}-$sherpaDesktop.jar" to hostNative.second,
)

fun sha256Hex(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { i ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = i.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

val fetchDesktopSherpa by tasks.registering {
    outputs.files(sherpaJars.map { refCache.resolve(it.first) })
    doLast {
        refCache.mkdirs()
        for ((name, sha) in sherpaJars) {
            val out = refCache.resolve(name)
            if (out.isFile && sha256Hex(out) == sha) continue
            val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaDesktop/$name"
            val tmp = refCache.resolve("$name.part")
            URI(url).toURL().openStream().use { i -> tmp.outputStream().use { i.copyTo(it) } }
            require(sha256Hex(tmp) == sha) { "sha256 mismatch for $name" }
            require(tmp.renameTo(out)) { "rename failed: $name" }
        }
    }
}
tasks.named("compileTestKotlin") { dependsOn(fetchDesktopSherpa) }

// 桌面版「下载的运行库」（sherpa-onnx C 接口 + onnxruntime 动态库），测试 NativeAsr 端到端；只有 macOS arm64 有对应包，其它主机跳过。
// Desktop copy of the downloadable runtime (C API + onnxruntime shared libs) for the NativeAsr end-to-end
// test; only macOS arm64 has a matching pack, other hosts skip it.
val desktopRuntimeName = "sherpa-onnx-v$sherpaDesktop-osx-arm64-shared-lib"
val desktopRuntimeSha = "ae77050cdae565496059d96f5ab33d77b397a0864282a4e4a26e3b3b3effb948"
val desktopRuntimeDir = refCache.resolve(desktopRuntimeName)
val fetchDesktopRuntime by tasks.registering {
    onlyIf { hostNative.first == "osx-aarch64" }
    outputs.dir(desktopRuntimeDir)
    doLast {
        if (desktopRuntimeDir.resolve("lib/libsherpa-onnx-c-api.dylib").exists()) return@doLast
        refCache.mkdirs()
        val archive = refCache.resolve("$desktopRuntimeName.tar.bz2")
        if (!(archive.isFile && sha256Hex(archive) == desktopRuntimeSha)) {
            val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaDesktop/$desktopRuntimeName.tar.bz2"
            val tmp = refCache.resolve("${archive.name}.part")
            URI(url).toURL().openStream().use { i -> tmp.outputStream().use { i.copyTo(it) } }
            require(sha256Hex(tmp) == desktopRuntimeSha) { "sha256 mismatch for ${archive.name}" }
            require(tmp.renameTo(archive)) { "rename failed: ${archive.name}" }
        }
        providers.exec { commandLine("tar", "xjf", archive.absolutePath, "-C", refCache.absolutePath) }.result.get()
        require(desktopRuntimeDir.resolve("lib/libsherpa-onnx-c-api.dylib").exists()) { "unexpected layout in ${archive.name}" }
    }
}

dependencies {
    testImplementation(files(sherpaJars.map { refCache.resolve(it.first) }))
    // org.json 在 Android 上是系统自带的；桌面 JVM 需要单独引入。 org.json ships with Android only.
    implementation("org.json:json:20240303")
}
