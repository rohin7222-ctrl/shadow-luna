pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "DeviceMemory"
include(":app")
include(":core-database")
include(":core-common")
include(":collector-appusage")
include(":collector-notifications")
include(":collector-photos")
include(":collector-videos")
include(":collector-audio")
include(":collector-location")
include(":collector-contacts-calendar")
include(":pipeline")
