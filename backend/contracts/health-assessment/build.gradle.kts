plugins {
    id("com.cookie.kotlin-library")
}

dependencies {
    api(platform(libs.spring.boot.dependencies))
    api(libs.nimbus.jose.jwt)
    api("tools.jackson.core:jackson-databind")
}
