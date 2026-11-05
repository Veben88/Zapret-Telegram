// Только JVM-модуль :core — для запуска юнит-тестов без Android SDK:
//   gradle -c settings-core.gradle.kts :core:test
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}
rootProject.name = "parts-trainer"
include(":core", ":omr")
