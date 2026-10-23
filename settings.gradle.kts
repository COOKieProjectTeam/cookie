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
    "apps:mobile:shared",
    "backend:platform:starter-web",
    "backend:platform:starter-postgres",
    "backend:platform:starter-messaging",
    "backend:platform:starter-testing",
    "backend:services:nutrition",
    "backend:services:identity",
    "backend:services:identity:domain",
    "backend:services:identity:application",
    "backend:services:notification",
    "backend:services:notification:domain",
    "backend:services:notification:application",
    "backend:tools:notification-sink",
)
