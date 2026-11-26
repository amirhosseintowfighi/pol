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
rootProject.name = "pol-android"
include(":app")
// The shared protocol/crypto core is its own Gradle build so it can be built and tested without the Android SDK.
includeBuild("../core")
