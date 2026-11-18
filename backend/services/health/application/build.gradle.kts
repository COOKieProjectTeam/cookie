plugins {
    id("com.cookie.kotlin-library")
}

dependencies {
    api(project(":backend:services:health:domain"))
    testImplementation(project(":backend:platform:starter-testing"))
}
