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
    dependsOn(":app:fetchBuiltinModels")
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

val refCache = rootProject.projectDir.resolve("../.ref/cache")

dependencies {
    // 桌面版 sherpa-onnx（Java API + macOS 原生库），只用于测试两遍识别流程。
    // Desktop sherpa-onnx (Java API + macOS natives), test-only, for the two-pass pipeline.
    testImplementation(files(refCache.resolve("sherpa-onnx-jvm-1.13.8.jar"), refCache.resolve("sherpa-onnx-native-lib-osx-aarch64-1.13.8.jar")))
    // org.json 在 Android 上是系统自带的；桌面 JVM 需要单独引入。 org.json ships with Android only.
    implementation("org.json:json:20240303")
}
