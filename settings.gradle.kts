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
rootProject.name = "MeshHome"
include(":core")
// Allows policy tests to run on machines without an Android SDK.
if (!providers.gradleProperty("coreOnly").isPresent) include(":app")
