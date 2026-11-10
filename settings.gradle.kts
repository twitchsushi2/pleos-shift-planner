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
        // 플레오스 Connect SDK(Vehicle 등). 인증 없이 받을 수 있다.
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/") {
            content { includeGroupByRegex("ai\\.pleos\\..*") }
        }
    }
}

rootProject.name = "PleosApp"

include(":app")
