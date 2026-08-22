plugins {
    id("com.cookie.kotlin-library")
    `java-test-fixtures`
}

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    api(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    api("org.junit.jupiter:junit-jupiter")
    api("org.junit.platform:junit-platform-launcher")
    api("org.assertj:assertj-core")
    api("org.testcontainers:testcontainers-postgresql")
    api("org.testcontainers:testcontainers")
}
