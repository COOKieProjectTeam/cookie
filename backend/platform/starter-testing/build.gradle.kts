plugins {
    id("com.cookie.kotlin-library")
    `java-test-fixtures`
}

dependencies {
    api(platform(libs.spring.boot.dependencies))
    api(platform(libs.testcontainers.bom))
    api("org.junit.jupiter:junit-jupiter")
    api("org.junit.platform:junit-platform-launcher")
    api("org.assertj:assertj-core")
    api("org.testcontainers:testcontainers-postgresql")
    api("org.testcontainers:testcontainers")
}
