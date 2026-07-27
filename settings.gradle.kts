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

rootProject.name = "MosaicAndroid"
include(":app")
include(":core:model")
include(":core:database")
include(":core:settings")
include(":feature:fit")
include(":feature:photos")
include(":health-connect-lab")
