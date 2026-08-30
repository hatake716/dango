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
        // sardine-android (WebDAV) は JitPack のみで配布されている
        maven("https://jitpack.io") {
            content { includeGroup("com.github.thegrizzlylabs") }
        }
    }
}

rootProject.name = "dango"
include(":app")
