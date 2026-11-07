pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // VOICEVOX CORE 的 java_packages.zip 解压到这里（见 构建说明.md 第 2 步）
        maven { url = uri("${rootDir}/third_party/maven") }
    }
}
rootProject.name = "RikkaVoice"
include(":app")
