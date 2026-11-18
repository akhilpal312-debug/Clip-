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

rootProject.name = "AIClipCreator"

include(":app")
include(":core-common")
include(":core-models")
include(":core-database")
include(":core-storage")
include(":core-network")
include(":core-audio")
include(":core-video")
include(":core-ai")
include(":core-data")
include(":feature-home")
include(":feature-video-import")
include(":feature-live-monitor")
include(":feature-analysis")
include(":feature-clips")
include(":feature-editor")
include(":feature-model-manager")
include(":feature-settings")
