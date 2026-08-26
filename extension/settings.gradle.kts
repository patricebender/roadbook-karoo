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
        // karoo-ext is published to GitHub Packages (auth required even though public).
        // Provide gpr.user / gpr.key in ~/.gradle/gradle.properties.
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                // Local dev: gpr.user / gpr.key in ~/.gradle/gradle.properties.
                // CI: GPR_USER / GPR_KEY env vars (see .github/workflows/release.yml).
                username = providers.gradleProperty("gpr.user").getOrElse(System.getenv("GPR_USER"))
                password = providers.gradleProperty("gpr.key").getOrElse(System.getenv("GPR_KEY"))
            }
        }
    }
}

rootProject.name = "roadbook"
include(":app")
