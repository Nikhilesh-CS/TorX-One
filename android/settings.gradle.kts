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
        maven { url = uri("https://raw.githubusercontent.com/guardianproject/gpmaven/master") }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "TorX One"
include(":app")

// Keep Gradle intermediates out of the OneDrive-synchronised working tree.
// Windows/OneDrive can otherwise hold packaging files open during release builds.
val localBuildRoot = File(System.getenv("LOCALAPPDATA"), "TorXOneGradleBuild")
gradle.beforeProject {
    layout.buildDirectory.set(localBuildRoot.resolve(name))
}
