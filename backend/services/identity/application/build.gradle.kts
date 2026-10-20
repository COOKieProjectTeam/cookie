plugins {
    id("com.cookie.kotlin-library")
}

dependencies {
    api(project(":backend:services:identity:domain"))
    testImplementation(project(":backend:platform:starter-testing"))
}
