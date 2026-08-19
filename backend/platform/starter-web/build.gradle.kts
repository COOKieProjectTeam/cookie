plugins {
    id("com.cookie.kotlin-library")
}

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.boot:spring-boot-starter-actuator")
}
