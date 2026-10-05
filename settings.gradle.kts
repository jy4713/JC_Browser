pluginManagement {
    repositories {
        google()
        mavenCentral()
        // ffmpeg-kit이 Maven Central에서 제거되어 미러 사용
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}
rootProject.name = "StreamBrowser"
include(":app")
