plugins {
    id("com.cookie.kotlin-library")
}

dependencies {
    api(project(":backend:services:progress:domain"))
    testImplementation(project(":backend:platform:starter-testing"))
}
