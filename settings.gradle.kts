pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "cookie"

includeBuild("build-logic")

include(
    "backend:platform:starter-web",
    "backend:platform:starter-postgres",
    "backend:platform:starter-messaging",
    "backend:platform:starter-testing",
    "backend:services:identity",
    "backend:tools:notification-sink",
)
