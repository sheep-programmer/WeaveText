pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "WeaveText"
include(":app")
// 桌面 JVM 上直接测试 JNI 绑定（加载 macOS/Linux 版 libweave）。 JNI tests on the desktop JVM.
include(":native-test")
// 独立的测试 APK：在已安装的正式包里加载语音模型，不把测试代码放进产品。
// Separate instrumentation APK testing speech inside the installed, minified release.
include(":voice-smoke")
