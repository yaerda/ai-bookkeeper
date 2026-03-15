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

rootProject.name = "ai-bookkeeper"

include(":app")
include(":core-common")
include(":core-data")
include(":feature-input")
include(":feature-capture")
include(":feature-stats")
include(":feature-sync")
