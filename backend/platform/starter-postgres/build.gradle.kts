plugins {
    id("com.cookie.kotlin-library")
}

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    api("org.springframework.boot:spring-boot-starter-flyway")
    api("org.springframework:spring-jdbc")
    api("com.zaxxer:HikariCP")
    api("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
}
