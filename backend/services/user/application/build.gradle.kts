plugins {
    id("com.cookie.kotlin-library")
}

dependencies {
    api(project(":backend:services:user:domain"))
    testImplementation(project(":backend:platform:starter-testing"))
}
