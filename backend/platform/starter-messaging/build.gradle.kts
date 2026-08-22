plugins {
    id("com.cookie.kotlin-library")
}

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    api("io.nats:jnats:2.26.1")
    api("tools.jackson.core:jackson-databind")
    testImplementation(project(":backend:platform:starter-testing"))
}
