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

// Use standard project-local outputs by default. Synced-folder users can opt into
// an external directory without forcing every checkout to share an AppData cache.
val externalBuildRoot = System.getenv("TORXONE_BUILD_ROOT")?.takeIf { it.isNotBlank() }
if (externalBuildRoot != null) {
    gradle.beforeProject {
        layout.buildDirectory.set(File(externalBuildRoot).resolve(name))
    }
}
