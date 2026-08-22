plugins {
    id("com.cookie.kotlin-library")
}

dependencies {
    implementation("com.ibm.icu:icu4j:78.3")
    testImplementation(project(":backend:platform:starter-testing"))
}
