plugins {
    id("com.cookie.kotlin-library")
}

dependencies {
    implementation(libs.icu4j)
    testImplementation(project(":backend:platform:starter-testing"))
}
