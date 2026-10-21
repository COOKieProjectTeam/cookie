plugins {
    id("com.cookie.kotlin-library")
}

dependencies {
    api(project(":backend:services:__SERVICE__:domain"))
    testImplementation(project(":backend:platform:starter-testing"))
}
