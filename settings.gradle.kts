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
    "platform:starter-web",
    "platform:starter-postgres",
    "platform:starter-messaging",
    "platform:starter-testing",
    "services:identity",
    "tools:notification-sink",
)
