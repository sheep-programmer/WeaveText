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
