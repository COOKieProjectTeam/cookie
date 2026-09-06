plugins {
    id("com.cookie.kotlin-library")
}

dependencies {
    api(platform(libs.spring.boot.dependencies))
    api(libs.jnats)
    api(libs.bouncycastle.provider)
    api("tools.jackson.core:jackson-databind")
    testImplementation(project(":backend:platform:starter-testing"))
}
