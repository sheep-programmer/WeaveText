import groovy.json.JsonSlurper
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.nio.ByteOrder
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val sdkDir: String = run {
    val p = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { p.load(it) }
    p.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: "${System.getProperty("user.home")}/Library/Android/sdk"
}
val ndkVer = "27.2.12479018"
val coreDir = rootProject.projectDir.resolve("../core")
val rustJniDir = layout.buildDirectory.dir("rustJniLibs").get().asFile
val dictAssetsDir = layout.buildDirectory.dir("dictAssets").get().asFile
val cargo = "${System.getProperty("user.home")}/.cargo/bin/cargo"
/** 可选：构建时内置的插件包目录（*.xipk）。不传则 APK 不含任何插件。
 *  Optional directory of *.xipk packages to bundle; without it the APK ships no plugins. */
val bundledPluginsDir: String? = (findProperty("weave.bundledPlugins") as String?)?.takeIf { it.isNotBlank() }
val pluginAssetsDir = layout.buildDirectory.dir("pluginAssets").get().asFile

// ---------------------------------------------------------------- 端侧模型 / on-device models
// 语音识别运行时 sherpa-onnx（Apache-2.0）与内置模型在构建时下载，经多个 GitHub 镜像回退并校验 SHA-256。
// The sherpa-onnx runtime (Apache-2.0) and built-in models are fetched at build time through
// several GitHub mirrors with SHA-256 verification.
/**
 * -Pweave.lite=true：轻量版，不带端侧语音识别运行时与模型（语音输入仍可用系统识别与插件），APK 约小 125 MB。
 * Lite build: no on-device speech runtime or models (voice still works via the system recognizer and
 * plugins); about 125 MB smaller.
 */
val liteBuild = (findProperty("weave.lite") as String?) == "true"

/** ABI 过滤在 AGP 里跨构建类型取并集，所以按本次要构建的类型决定。 AGP unions ABI filters, so decide per invocation. */
val releaseBuild = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

fun abiList(isRelease: Boolean): List<String> =
    (findProperty("weave.abis") as String?)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: if (isRelease) listOf("arm64-v8a") else listOf("arm64-v8a", "x86_64")

val sherpaVersion = "1.13.8"
val sherpaAarUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
val sherpaAarSha = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
val sherpaAar = rootProject.projectDir.resolve("../.ref/cache/sherpa-onnx-$sherpaVersion.aar")
val modelAssetsDir = layout.buildDirectory.dir("modelAssets").get().asFile
val catalogFile = project.file("src/main/assets/models/catalog.json")

@Suppress("UNCHECKED_CAST")
fun catalog(): Map<String, Any> = JsonSlurper().parse(catalogFile) as Map<String, Any>

fun sha256(f: File): String {
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

/** 依次尝试镜像下载并校验；已存在且校验通过则跳过。 Try each mirror; skip if already verified. */
fun fetchVerified(url: String, sha: String, dest: File) {
    if (dest.isFile && sha256(dest) == sha) return
    dest.parentFile.mkdirs()
    @Suppress("UNCHECKED_CAST")
    val mirrors = (catalog()["mirrors"] as List<Map<String, String>>).map { it["template"]!! }
    val errors = mutableListOf<String>()
    for (t in mirrors) {
        val src = t.replace("{url}", url)
        val part = File(dest.path + ".part")
        try {
            val conn = URI(src).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.inputStream.use { i -> part.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            val got = sha256(part)
            if (got != sha) throw GradleException("sha256 mismatch ($got)")
            part.renameTo(dest)
            logger.lifecycle("fetched ${dest.name} via $src")
            return
        } catch (e: Exception) {
            part.delete()
            errors += "$src: ${e.message}"
        }
    }
    throw GradleException("could not fetch $url:\n" + errors.joinToString("\n"))
}

/** 下载运行时 AAR。 Fetch the runtime AAR. */
val fetchSherpa by tasks.registering {
    group = "weave"
    outputs.file(sherpaAar)
    onlyIf { !liteBuild }
    doLast { fetchVerified(sherpaAarUrl, sherpaAarSha, sherpaAar) }
}

/** 下载并解出内置模型到 assets/models/<id>/。 Fetch built-in models into assets/models/<id>/. */
/** 下载（带缓存与校验）并解出一个模型到 outRoot/models/<id>。 Fetch (cached, verified) and extract one model. */
fun extractModel(m: Map<String, Any>, outRoot: File, workDir: File) {
    val id = m["id"] as String
    @Suppress("UNCHECKED_CAST")
    val archive = (m["archive"] ?: (m["archives"] as List<Map<String, Any>>).first()) as Map<String, Any>
    @Suppress("UNCHECKED_CAST")
    val fileSpecs = m["files"] as List<Map<String, Any>>
    val files = fileSpecs.map { it["name"] as String }
    val url = archive["url"] as String
    val cached = rootProject.projectDir.resolve("../.ref/cache/" + url.substringAfterLast('/'))
    fetchVerified(url, archive["sha256"] as String, cached)
    val out = outRoot.resolve("models/$id")
    val ok = { fileSpecs.all { f -> out.resolve(f["name"] as String).let { it.isFile && sha256(it) == f["sha256"] } } }
    if (ok()) return
    val tmp = workDir.resolve(id).apply { deleteRecursively(); mkdirs() }
    providers.exec { commandLine("tar", "xjf", cached.absolutePath, "-C", tmp.absolutePath) }.result.get()
    out.mkdirs()
    for (f in files) {
        val found = tmp.walkTopDown().firstOrNull { it.isFile && it.name == f }
            ?: throw GradleException("$id: $f not found in archive")
        found.copyTo(out.resolve(f), overwrite = true)
    }
    tmp.deleteRecursively()
    if (!ok()) throw GradleException("$id: extracted files do not match catalog sha256")
    logger.lifecycle("model $id ready")
}

val fetchBuiltinModels by tasks.registering {
    group = "weave"
    inputs.file(catalogFile)
    // 轻量版与离线语音版的输出不同，必须作为输入，否则先构建轻量版后语音版会沿用空目录。
    // Lite and voice produce different outputs; without this input a voice build after a lite one reuses the empty dir.
    inputs.property("lite", liteBuild)
    outputs.dir(modelAssetsDir)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val models = (catalog()["models"] as List<Map<String, Any>>).filter { it["builtin"] == true && !liteBuild }
        // 不再内置的模型从资源目录里清掉。 Drop models that are no longer built in.
        val keep = models.map { it["id"] as String }.toSet()
        modelAssetsDir.resolve("models").listFiles()?.filter { it.isDirectory && it.name !in keep }?.forEach { it.deleteRecursively() }
        for (m in models) extractModel(m, modelAssetsDir, temporaryDir)
    }
}

/** 桌面测试用的模型（与是否内置无关）。 Models for the desktop tests, independent of what is built in. */
val testModelsDir = layout.buildDirectory.dir("testModels").get().asFile
val fetchTestModels by tasks.registering {
    group = "weave"
    inputs.file(catalogFile)
    outputs.dir(testModelsDir)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val models = (catalog()["models"] as List<Map<String, Any>>).filter { it["id"] in setOf("asr-stream-small", "asr-final-small") }
        for (m in models) extractModel(m, testModelsDir, temporaryDir)
    }
}

android {
    namespace = "com.weavetext.ime"
    compileSdk = 36
    ndkVersion = ndkVer

    defaultConfig {
        applicationId = "com.weavetext.ime"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.0-beta.5"
        // 调试版打 arm64（真机）+ x86_64（模拟器）；正式版只打 arm64，可用 -Pweave.abis=… 覆盖。
        // Debug: arm64 + x86_64 (emulators); release: arm64 only, override with -Pweave.abis=….
        ndk { abiFilters += abiList(isRelease = releaseBuild) }
        buildConfigField("boolean", "LOCAL_ASR", (!liteBuild).toString())
    }

    // 正式签名：读取 android/keystore.properties（不入库）；没有则退回调试签名。
    // Release signing from the git-ignored android/keystore.properties; falls back to the debug key.
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (keystoreProps != null) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets["main"].jniLibs.srcDir(rustJniDir)
    sourceSets["main"].assets.srcDir(dictAssetsDir)
    sourceSets["main"].assets.srcDir(pluginAssetsDir)
    if (!liteBuild) sourceSets["main"].assets.srcDir(modelAssetsDir)
    // 端侧语音适配层：轻量版换成空实现，不依赖 sherpa-onnx。 Lite swaps the ASR adapter for a stub.
    sourceSets["main"].java.srcDir(if (liteBuild) "src/nosherpa/java" else "src/sherpa/java")
    packaging {
        // 离线语音版的原生库（约 31 MB）压缩存放，安装包小一半以上；轻量版只有 4 MB 的内核库，保持不压缩直接映射。
        // The voice build compresses its ~31 MB of native libraries; lite keeps its single 4 MB library uncompressed.
        jniLibs.useLegacyPackaging = !liteBuild
        // sherpa-onnx 的 JNI 库只依赖 onnxruntime，C/C++ API 库用不到。 JNI lib needs only onnxruntime.
        jniLibs.excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
    }
    androidResources {
        // 模型文件不压缩：ONNX 压缩率很低，且可以直接从 APK 读取。 Keep ONNX uncompressed.
        // 词库是分块压缩文件（.wvz），在 APK 内原样存放，由内核直接按偏移读取，不再解压到手机上。
        // Dictionaries are block-compressed (.wvz), stored as-is and read by offset straight from the APK.
        noCompress += listOf("onnx", "wvz")
    }
    // JVM 截图测试（Robolectric 原生渲染 + Roborazzi），输出到 src/test/snapshots。
    // JVM screenshot tests (Robolectric native graphics + Roborazzi), written to src/test/snapshots.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("weave.snapshotDir", project.file("src/test/snapshots").absolutePath)
                it.maxHeapSize = "4g"
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

/** 可选：跳过 Rust 编译，沿用上次产物（JVM 测试不需要原生库；内核改动进行中时用）。
 *  Optional: skip the Rust build and reuse the last output (JVM tests don't need native code). */
val skipRust = (findProperty("weave.skipRust") as String?)?.toBoolean() == true

/** 交叉编译 Rust 内核。 Cross-compile the Rust core. */
val buildRust by tasks.registering(Exec::class) {
    group = "weave"
    onlyIf { !skipRust }
    workingDir = coreDir
    environment("ANDROID_NDK_HOME", "$sdkDir/ndk/$ndkVer")
    // 只编本次要打包的 ABI（正式版只有 arm64）。 Build only the ABIs being packaged (release: arm64).
    val targets = abiList(isRelease = releaseBuild).flatMap { listOf("-t", it) }
    commandLine(listOf(cargo, "ndk") + targets + listOf("-o", rustJniDir.absolutePath, "build", "-p", "weave-ffi", "--release"))
    inputs.dir(coreDir.resolve("weave-engine/src"))
    inputs.dir(coreDir.resolve("weave-dict/src"))
    inputs.dir(coreDir.resolve("weave-ffi/src"))
    inputs.dir(coreDir.resolve("weave-plugin/src"))
    outputs.dir(rustJniDir)
}

/**
 * 编译词库到 data/build（build.sh 自己判断是否需要重建），再同步进 assets/dict。
 * 注意：Exec 任务不声明输入时 Gradle 会一直认为它是最新的，所以这里强制每次执行，由脚本决定是否真的重建。
 * Compile dictionaries into data/build (build.sh decides whether anything changed), then sync them
 * into assets/dict. An Exec task without declared inputs would stay "up to date" forever, so it always
 * runs and the script does its own change detection.
 */
val dataBuildDir = rootProject.projectDir.resolve("../data/build")
val buildDicts by tasks.registering(Exec::class) {
    group = "weave"
    commandLine(rootProject.projectDir.resolve("../data/build.sh").absolutePath, dataBuildDir.absolutePath)
    outputs.upToDateWhen { false }
}

val syncDicts by tasks.registering(Sync::class) {
    group = "weave"
    dependsOn(buildDicts)
    from(dataBuildDir) {
        include("*.wvz")
    }
    into(dictAssetsDir.resolve("dict"))
    // 缺文件或格式版本不对就让构建失败，避免打出一个没有词库的 APK。
    // Fail the build on missing data or a wrong format version instead of shipping an APK without a dictionary.
    doLast {
        fun head(f: File): ByteBuffer {
            require(f.isFile && f.length() > 64) { "missing dictionary file: ${f.name}" }
            return ByteBuffer.wrap(f.inputStream().use { it.readNBytes(8) }).order(ByteOrder.LITTLE_ENDIAN)
        }
        fun check(f: File, magic: String, version: Int) {
            val h = head(f)
            require(String(h.array(), 0, 4, Charsets.US_ASCII) == magic) { "bad magic in ${f.name}" }
            require(h.getInt(4) == version) { "${f.name} has format version ${h.getInt(4)}, engine expects $version" }
        }
        // 原始文件的格式版本（打包前）与打包后的容器头。 Raw format versions, then the packed containers.
        for (n in listOf("pinyin.wvl", "wubi86.wvl", "english.wvl")) check(dataBuildDir.resolve(n), "WVLX", 4)
        check(dataBuildDir.resolve("grammar.wvg"), "WVGM", 1)
        val dir = dictAssetsDir.resolve("dict")
        for (key in listOf("pinyin", "wubi86", "english", "grammar", "st_phrases", "st_characters", "emoji", "hand", "hand_net", "follow")) {
            check(dir.resolve("$key.wvz"), "WVPK", 1)
        }
    }
}

/** 拷贝可选的内置插件到 assets/plugins。 Copy optional bundled plugins into assets/plugins. */
val bundlePlugins by tasks.registering(Sync::class) {
    group = "weave"
    into(pluginAssetsDir.resolve("plugins"))
    bundledPluginsDir?.let { from(it) { include("*.xipk") } }
}

tasks.named("preBuild") { dependsOn(buildRust, syncDicts, bundlePlugins, fetchSherpa, fetchBuiltinModels) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // 端侧语音识别运行时（构建时下载，见 fetchSherpa；轻量版不带）。 On-device ASR runtime; not in lite.
    if (!liteBuild) implementation(files(sherpaAar))
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.75.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.75.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core-ktx:1.7.0")
}
