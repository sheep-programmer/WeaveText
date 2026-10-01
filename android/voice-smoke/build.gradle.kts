import java.util.Properties

plugins { id("com.android.application") }

android {
    namespace = "com.weavetext.ime.voicesmoke"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.weavetext.ime.voicesmoke"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
    // Android 要求 instrumentation 与被测正式包签名相同。 Same signature as the target release APK.
    val props = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (props != null) create("release") {
            storeFile = project(":app").file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    }
    buildTypes {
        release { signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Optional, local-only fixtures for a downloaded-pack UI test. Never part of the product APK.
    (findProperty("weave.voiceSmokePack") as String?)?.let { sourceSets["main"].assets.srcDir(it) }
}
