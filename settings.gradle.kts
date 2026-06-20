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
        // telegram4j-core is only published as a snapshot
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots") {
            content { includeGroup("com.telegram4j") }
        }
        // fallback mirror for some transitive deps
        maven("https://jitpack.io")
    }
}

rootProject.name = "tgstream"
include(":app")
