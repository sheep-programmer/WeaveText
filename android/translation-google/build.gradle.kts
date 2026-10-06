import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val translationAbis = (findProperty("weave.abis") as String?)?.split(',')?.map { it.trim() }
    ?.filter { it.isNotEmpty() }?.distinct()?.takeIf { it.isNotEmpty() } ?: listOf("arm64-v8a")
require(translationAbis.all { it in setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }) { "Invalid weave.abis for translation plugin" }

android {
    namespace = "com.weavetext.translate.google"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.weavetext.translate.google"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += translationAbis }
    }
    // Same keystore resolution as :app and :voice-smoke; debug uses AGP's default debug key.
    val props = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }
    signingConfigs {
        if (props != null) create("release") {
            storeFile = project(":app").file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":translation-contract"))
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
}
