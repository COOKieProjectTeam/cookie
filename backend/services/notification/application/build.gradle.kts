plugins {
    id("com.cookie.kotlin-library")
}

dependencies {
    api(project(":backend:services:notification:domain"))
    testImplementation(project(":backend:platform:starter-testing"))
}
